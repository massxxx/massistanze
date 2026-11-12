# -*- coding: utf-8 -*-
"""MassiStanze - musica sincronizzata in tutte le stanze.

Il PC fa da centro: tiene la musica (MP3 caricati dai telefoni), le radio online
e la coda; decodifica l'audio e lo manda, con l'ora esatta in cui suonarlo, a
tutti i dispositivi collegati (telefoni, browser, anche il PC stesso).
Ogni dispositivo è una "stanza" con nome, volume, acceso/spento e ritardo.
Annunci col microfono: la voce arriva in tutte le stanze e la musica si abbassa.

Telefono: http://IP-DEL-PC:8766   PC: http://localhost:8766
Librerie: aiohttp, numpy, av (PyAV, contiene FFmpeg).
"""
import asyncio
import collections
import json
import os
import random
import re
import secrets
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import webbrowser

import av
import numpy as np
from aiohttp import web, WSMsgType

VERSIONE = "1.0"
PORTA = int(os.environ.get("MASSISTANZE_PORTA", "8766"))
WINDOWS = sys.platform.startswith("win")
RATE = 48000                 # campioni al secondo
PEZZO = 4800                 # 100 ms per pacchetto
DUR = PEZZO / RATE
ANTICIPO = 1.0               # secondi di musica mandati in anticipo
ANTICIPO_VOCE = 0.40         # durante un annuncio (la voce arriva prima)
TAGLIO = 0.30                # da quando valgono pausa / salto / cambio brano
ABBASSA = 0.25               # musica durante l'annuncio
ESTENSIONI = (".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma", ".mp4", ".webm", ".3gp", ".amr")

# ---------------------------------------------------------------- percorsi
if getattr(sys, "frozen", False):
    BASE = getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
else:
    BASE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(BASE, "web")
if not os.path.isdir(WEB):
    WEB = os.path.join(os.path.dirname(BASE), "web")

if os.environ.get("MASSISTANZE_DATI"):
    DATI = os.environ["MASSISTANZE_DATI"]
elif WINDOWS:
    DATI = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "MassiStanze")
else:
    DATI = os.path.join(os.path.expanduser("~"), ".massistanze")
MUSICA = os.path.join(DATI, "musica")
os.makedirs(MUSICA, exist_ok=True)
FILE_STATO = os.path.join(DATI, "stato.json")
FILE_SEGRETI = os.path.join(DATI, "accesso.json")

T0 = time.monotonic()


def ora():
    """Orologio comune (secondi). Tutti i dispositivi si allineano a questo."""
    return time.monotonic() - T0


REGISTRO = []


def log(msg):
    riga = time.strftime("%H:%M:%S") + "  " + msg
    REGISTRO.append(riga)
    del REGISTRO[:-40]
    try:
        print(riga, flush=True)
    except Exception:
        pass


def carica_json(percorso, predefinito):
    try:
        with open(percorso, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return predefinito


def salva_json(percorso, dati):
    tmp = percorso + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(dati, f, ensure_ascii=False, indent=1)
    os.replace(tmp, percorso)


# ---------------------------------------------------------------- dati salvati
D = carica_json(FILE_STATO, {})
D.setdefault("brani", [])          # {id, file, titolo, artista, durata, da}
D.setdefault("playlist", [])       # {id, nome, brani:[id]}
D.setdefault("radio", [])          # preferite {id, nome, url, icona, info}
D.setdefault("stanze", {})         # id -> {nome, volume, attivo, ritardo}
D.setdefault("coda", [])
D.setdefault("indice", 0)
D.setdefault("ripeti", "no")       # no | tutto | uno
D.setdefault("casuale", False)
D.setdefault("master", 80)
D.setdefault("chat", [])         # {id, cid, da, testo, ora}
D["brani"] = [b for b in D["brani"] if os.path.isfile(os.path.join(MUSICA, b.get("file", "")))]

SEGRETI = carica_json(FILE_SEGRETI, {})
if not SEGRETI.get("pin"):
    SEGRETI = {"pin": "%04d" % random.SystemRandom().randint(0, 9999), "token": []}
    salva_json(FILE_SEGRETI, SEGRETI)

_salva_presto = None


def salva():
    """Salva tra poco (più modifiche di fila = un solo salvataggio)."""
    global _salva_presto
    if _salva_presto:
        _salva_presto.cancel()
    _salva_presto = asyncio.get_running_loop().call_later(0.8, lambda: salva_json(FILE_STATO, D))


def brano(bid):
    for b in D["brani"]:
        if b["id"] == bid:
            return b
    return None


# ---------------------------------------------------------------- decodifica
class Sorgente:
    """Decodifica un file o una radio in un thread: PCM 48 kHz stereo int16."""

    def __init__(self, url, inizio=0.0, radio=False, prova=False):
        self.url, self.inizio, self.radio, self.prova = url, max(0.0, inizio), radio, prova
        self.lock = threading.Lock()
        self.pezzi = collections.deque()
        self.n = 0
        self.consumati = 0
        self.finita = False
        self.errore = None
        self.ferma = False
        self.pronta = not radio
        self.durata = None
        self.titolo_radio = ""
        threading.Thread(target=self._gira, daemon=True).start()

    def chiudi(self):
        self.ferma = True

    @property
    def pos(self):
        return self.inizio + self.consumati / RATE

    @property
    def esaurita(self):
        return self.finita and self.n == 0

    def _metti(self, a):
        while not self.ferma and not self.radio and self.n > 8 * RATE:
            time.sleep(0.05)
        with self.lock:
            self.pezzi.append(a)
            self.n += len(a)
            if self.radio:
                while self.n > 12 * RATE:          # la radio corre più del nostro orologio: butta il vecchio
                    v = self.pezzi.popleft()
                    self.n -= len(v)
                if not self.pronta and self.n >= int(1.5 * RATE):
                    self.pronta = True

    def _gira(self):
        c = None
        try:
            if self.prova:
                self._prova()
                return
            opz = {}
            if self.radio:
                opz = {"reconnect": "1", "reconnect_streamed": "1", "reconnect_delay_max": "5",
                       "user_agent": "MassiStanze/" + VERSIONE, "icy": "1"}
            c = av.open(self.url, options=opz, timeout=(15, 30) if self.radio else None)
            s = c.streams.audio[0]
            if c.duration and not self.radio:
                self.durata = c.duration / 1e6
            if self.inizio > 0:
                c.seek(int(self.inizio * 1e6))
            res = av.AudioResampler(format="s16", layout="stereo", rate=RATE)
            ultimo_meta = 0
            for pacchetto in c.demux(s):
                if self.ferma:
                    break
                try:
                    frames = pacchetto.decode()
                except av.error.FFmpegError:
                    continue
                for fr in frames:
                    if self.inizio > 0 and fr.time is not None and fr.time + fr.samples / fr.sample_rate < self.inizio:
                        continue
                    for g in res.resample(fr):
                        self._metti(g.to_ndarray().reshape(-1, 2))
                if self.radio and time.time() - ultimo_meta > 5:
                    ultimo_meta = time.time()
                    try:
                        t = (c.metadata or {}).get("StreamTitle") or ""
                        self.titolo_radio = t.strip()
                    except Exception:
                        pass
            if not self.ferma:
                for g in res.resample(None):
                    self._metti(g.to_ndarray().reshape(-1, 2))
        except Exception as e:
            self.errore = str(e) or e.__class__.__name__
        finally:
            if c is not None:
                try:
                    c.close()
                except Exception:
                    pass
            self.finita = True
            self.pronta = True

    def _prova(self):
        """Un "bip" a ogni secondo: serve a regolare il ritardo delle stanze a orecchio."""
        t = np.arange(PEZZO) / RATE
        bip = (np.sin(2 * np.pi * 1000 * t[:2400]) * 12000 * np.hanning(2400)).astype(np.int16)
        k = 0
        while not self.ferma and k < 600:
            for i in range(10):
                a = np.zeros((PEZZO, 2), np.int16)
                if i == 0:
                    a[:2400, 0] = bip
                    a[:2400, 1] = bip
                self._metti(a)
            k += 1

    def prendi(self, k):
        with self.lock:
            if not self.pronta:
                return None
            if self.n < k:
                if self.finita and self.n > 0:
                    resto = np.concatenate(list(self.pezzi))
                    self.pezzi.clear()
                    self.consumati += self.n
                    self.n = 0
                    out = np.zeros((k, 2), np.int16)
                    out[:len(resto)] = resto
                    return out
                if self.radio and not self.finita:
                    self.pronta = False            # buco nella radio: riempi di nuovo il serbatoio
                return None
            parti, serve = [], k
            while serve > 0:
                p = self.pezzi[0]
                if len(p) <= serve:
                    parti.append(p)
                    self.pezzi.popleft()
                    serve -= len(p)
                else:
                    parti.append(p[:serve])
                    self.pezzi[0] = p[serve:]
                    serve = 0
            self.n -= k
            self.consumati += k
            return np.concatenate(parti) if len(parti) > 1 else parti[0]


def leggi_info(percorso, nome_file):
    titolo, artista, durata = os.path.splitext(nome_file)[0], "", 0
    c = av.open(percorso)
    try:
        if not c.streams.audio:
            raise ValueError("nel file non c'è audio")
        m = {k.lower(): v for k, v in (c.metadata or {}).items()}
        titolo = (m.get("title") or titolo).strip()[:120]
        artista = (m.get("artist") or m.get("album_artist") or "").strip()[:120]
        durata = (c.duration or 0) / 1e6
    finally:
        c.close()
    if not artista and " - " in titolo:
        artista, titolo = [x.strip() for x in titolo.split(" - ", 1)]
    return titolo, artista, round(durata, 1)


# ---------------------------------------------------------------- regia
class Cliente:
    def __init__(self, ws, cid, nome, locale):
        self.ws, self.id, self.nome, self.locale = ws, cid, nome, locale
        self.coda = asyncio.Queue(maxsize=80)
        self.mic = False

    def manda(self, dati):
        try:
            self.coda.put_nowait(dati)
        except asyncio.QueueFull:
            pass                                    # telefono troppo lento: salta il pacchetto

    async def scrittore(self):
        while True:
            d = await self.coda.get()
            if d is None:
                return
            try:
                if isinstance(d, (bytes, bytearray)):
                    await self.ws.send_bytes(d)
                else:
                    await self.ws.send_str(d)
            except Exception:
                return


CLIENTI = {}          # id -> Cliente


class Regia:
    def __init__(self):
        self.sorgente = None
        self.fonte = None              # {"tipo":"brano","id"} | {"tipo":"radio",...} | {"tipo":"prova"}
        self.suona = False
        self.prossimo = 0.0            # ora del prossimo pacchetto da mandare
        self.storia = collections.deque()   # pacchetti già mandati: per pausa, annunci, nuovi arrivi
        self.rimetti = collections.deque()  # musica tolta da una pausa, da rimandare
        self.posT = (0.0, 0.0)         # (ora, posizione nel brano) dell'ultimo pacchetto
        self.annuncio = None           # {"id", "mic": array float, "pronto", "fine"}
        self.avviso = ""
        self.inizio_brano = 0.0

    # -- stato per le pagine
    def stato(self):
        f = dict(self.fonte or {})
        if f.get("tipo") == "brano":
            b = brano(f.get("id")) or {}
            f.update(titolo=b.get("titolo", "?"), artista=b.get("artista", ""), durata=b.get("durata", 0))
        if f.get("tipo") == "radio" and self.sorgente:
            f["in_onda"] = self.sorgente.titolo_radio
            f["carica"] = not self.sorgente.pronta
        stanze = []
        for sid, s in D["stanze"].items():
            stanze.append(dict(s, id=sid, online=sid in CLIENTI, mic=sid in CLIENTI and CLIENTI[sid].mic))
        stanze.sort(key=lambda s: (not s["online"], s.get("nome", "").lower()))
        return {"t": "stato", "suona": self.suona, "fonte": f or None, "posT": self.posT[0], "pos": self.posT[1],
                "coda": D["coda"], "indice": D["indice"], "ripeti": D["ripeti"], "casuale": D["casuale"],
                "master": D["master"], "stanze": stanze,
                "annuncio": (self.annuncio or {}).get("id"), "avviso": self.avviso}

    def libreria(self):
        return {"t": "libreria", "brani": D["brani"], "playlist": D["playlist"], "radio": D["radio"]}

    def diffondi(self, msg):
        s = json.dumps(msg, ensure_ascii=False)
        for c in list(CLIENTI.values()):
            c.manda(s)

    def annuncia_stato(self):
        self.diffondi(self.stato())

    # -- taglio: da "da" in poi i pacchetti già mandati non valgono più
    def taglia(self, conserva=True):
        da = ora() + TAGLIO
        tolti = [h for h in self.storia if h["T"] >= da]
        for _ in tolti:
            self.storia.pop()
        if tolti:
            da = tolti[0]["T"]
        if conserva:
            self.rimetti.extendleft(reversed([h for h in tolti if h["musica"] is not None]))
        else:
            self.rimetti.clear()
        self.prossimo = max(da, ora() + TAGLIO)
        self.diffondi({"t": "taglia", "da": da})
        return tolti

    # -- comandi
    def apri_brano(self, indice, inizio=0.0, taglia=True):
        if taglia:
            self.taglia(conserva=False)
        self.chiudi_sorgente()
        self.avviso = ""
        if not D["coda"]:
            self.fonte = None
            self.suona = False
            return
        indice %= len(D["coda"])
        D["indice"] = indice
        b = brano(D["coda"][indice])
        if not b:
            del D["coda"][indice]
            return self.apri_brano(indice, 0, False)
        self.sorgente = Sorgente(os.path.join(MUSICA, b["file"]), inizio)
        self.fonte = {"tipo": "brano", "id": b["id"]}
        self.suona = True
        salva()

    def apri_radio(self, r):
        self.taglia(conserva=False)
        self.chiudi_sorgente()
        self.avviso = ""
        self.sorgente = Sorgente(r["url"], radio=True)
        self.fonte = {"tipo": "radio", "nome": r.get("nome", "Radio"), "url": r["url"], "icona": r.get("icona", ""),
                      "info": r.get("info", "")}
        self.suona = True

    def apri_prova(self):
        self.taglia(conserva=False)
        self.chiudi_sorgente()
        self.sorgente = Sorgente(None, prova=True)
        self.fonte = {"tipo": "prova", "titolo": "Prova sincronia", "artista": "un bip ogni secondo"}
        self.suona = True

    def chiudi_sorgente(self):
        if self.sorgente:
            self.sorgente.chiudi()
        self.sorgente = None
        self.rimetti.clear()

    def avanti(self, salto=1, da_solo=False):
        """Brano successivo (salto=1) o precedente (salto=-1)."""
        if not D["coda"] or (self.fonte or {}).get("tipo") not in ("brano", None):
            if da_solo:
                self.chiudi_sorgente()
                self.suona = False
                self.fonte = None
            return
        n = len(D["coda"])
        i = D["indice"]
        if da_solo and D["ripeti"] == "uno":
            nuovo = i
        elif D["casuale"] and n > 1 and salto > 0:
            nuovo = random.choice([k for k in range(n) if k != i])
        else:
            nuovo = i + salto
        if nuovo >= n or nuovo < 0:
            if D["ripeti"] == "tutto" or not da_solo:
                nuovo %= n
            else:                                    # fine della coda
                self.chiudi_sorgente()
                self.suona = False
                D["indice"] = 0
                self.fonte = {"tipo": "brano", "id": D["coda"][0]}
                self.posT = (ora(), 0.0)
                return
        self.apri_brano(nuovo, taglia=not da_solo)

    def play(self):
        if self.suona:
            return
        if self.fonte and self.fonte.get("tipo") == "radio":
            return self.apri_radio(self.fonte)
        if self.sorgente and not self.sorgente.esaurita:
            self.suona = True
            self.prossimo = max(self.prossimo, ora() + TAGLIO)
            return
        if D["coda"]:
            self.apri_brano(D["indice"], self.posT[1] if (self.fonte or {}).get("tipo") == "brano" else 0)

    def pausa(self):
        if not self.suona:
            return
        tolti = self.taglia(conserva=True)
        if tolti:
            self.posT = (tolti[0]["T"], tolti[0]["pos"])
        self.suona = False
        if (self.fonte or {}).get("tipo") == "radio":
            self.chiudi_sorgente()

    # -- produzione dei pacchetti
    def prendi_musica(self):
        if self.rimetti:
            h = self.rimetti.popleft()
            return h["musica"], h["pos"]
        s = self.sorgente
        if not s:
            return None, 0.0
        pos = s.pos
        m = s.prendi(PEZZO)
        if m is not None:
            return m, pos
        if s.esaurita:
            if s.errore:
                self.avviso = "Non riesco a suonare: " + s.errore[:120]
                log("✖ " + self.avviso)
            if self.fonte and self.fonte.get("tipo") == "brano":
                self.avanti(1, da_solo=True)
                self.annuncia_stato()
                return "ancora", 0.0
            self.chiudi_sorgente()
            self.suona = False
            self.annuncia_stato()
        return None, 0.0

    def prendi_voce(self):
        a = self.annuncio
        if not a:
            return None
        if not a["pronto"]:
            if len(a["mic"]) >= int(0.15 * RATE) or a["fine"]:
                a["pronto"] = True
            else:
                return np.zeros(PEZZO, np.float32)
        v = a["mic"][:PEZZO]
        a["mic"] = a["mic"][PEZZO:]
        if len(v) < PEZZO:
            v = np.concatenate([v, np.zeros(PEZZO - len(v), np.float32)])
            if a["fine"] and len(a["mic"]) == 0:
                self.annuncio = None
                log("🎤 Annuncio finito")
                self.annuncia_stato()
        return v

    def produci(self):
        adesso = ora()
        if not (self.suona or self.annuncio):
            return
        anticipo = ANTICIPO_VOCE if self.annuncio else ANTICIPO
        if self.prossimo < adesso + 0.2:
            self.prossimo = adesso + 0.25
        giri = 0
        while self.prossimo < adesso + anticipo and giri < 40:
            giri += 1
            musica, pos = (None, 0.0)
            if self.suona:
                musica, pos = self.prendi_musica()
                if isinstance(musica, str):
                    continue
            if musica is None and not self.annuncio:
                break
            voce = self.prendi_voce()
            h = {"T": self.prossimo, "musica": musica, "voce": voce, "pos": pos,
                 "chi": (self.annuncio or {}).get("id"), "mix": {}}
            self.storia.append(h)
            while self.storia and self.storia[0]["T"] < adesso - 3:
                self.storia.popleft()
            if musica is not None:
                self.posT = (self.prossimo, pos)
            for c in list(CLIENTI.values()):
                b = pacchetto(h, c)
                if b:
                    c.manda(b)
            self.prossimo += DUR

    # -- annunci
    def inizia_annuncio(self, cid):
        if self.annuncio and self.annuncio["id"] != cid:
            return False
        self.annuncio = {"id": cid, "mic": np.zeros(0, np.float32), "pronto": False, "fine": False}
        # la musica già mandata viene ripresa: si abbassa subito e la voce arriva prima
        self.taglia(conserva=True)
        log("🎤 Annuncio da %s" % D["stanze"].get(cid, {}).get("nome", "?"))
        return True

    def voce(self, cid, campioni, freq):
        a = self.annuncio
        if not a or a["id"] != cid or a["fine"]:
            return
        x = campioni.astype(np.float32)
        if freq != RATE and len(x) > 1:
            n = int(round(len(x) * RATE / freq))
            x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
        a["mic"] = np.concatenate([a["mic"], x])[-RATE * 3:]

    def fine_annuncio(self, cid):
        if self.annuncio and self.annuncio["id"] == cid:
            self.annuncio["fine"] = True
            self.annuncio["pronto"] = True


R = Regia()


def variante(h, c):
    st = D["stanze"].get(c.id, {})
    attivo = st.get("attivo", True)
    if h["chi"]:
        if c.id == h["chi"]:
            return "senza_voce" if attivo and h["musica"] is not None else None
        return "tutto" if attivo else "solo_voce"
    if h["musica"] is None:
        return None
    return "tutto" if attivo else None


def pacchetto(h, c):
    v = variante(h, c)
    if not v:
        return None
    b = h["mix"].get(v)
    if b is None:
        if h["chi"] is None and h["musica"] is not None:
            pcm = h["musica"].tobytes()
        else:
            if v == "solo_voce" or h["musica"] is None:
                base = np.zeros((PEZZO, 2), np.float32)
            else:
                base = h["musica"].astype(np.float32) * ABBASSA
            if v != "senza_voce" and h["voce"] is not None:
                base += h["voce"][:, None]
            pcm = np.clip(base, -32768, 32767).astype(np.int16).tobytes()
        b = struct.pack("<BxxxId", 1, 0, h["T"]) + pcm
        h["mix"][v] = b
    return b


def recupera(c):
    """Un telefono appena arrivato (o riacceso) riceve subito la musica già in viaggio."""
    lim = ora() + 0.15
    for h in list(R.storia):
        if h["T"] > lim:
            b = pacchetto(h, c)
            if b:
                c.manda(b)


async def produttore():
    while True:
        await asyncio.sleep(0.02)
        try:
            R.produci()
        except Exception as e:
            log("✖ errore audio: %s" % e)
            R.chiudi_sorgente()
            R.suona = False
            R.annuncio = None
            R.annuncia_stato()


async def aggiornatore():
    """Ogni tanto manda lo stato (titolo della radio, caricamento)."""
    ultimo = ""
    while True:
        await asyncio.sleep(2)
        s = R.stato()
        firma = json.dumps([s["fonte"], s["suona"]], ensure_ascii=False)
        if firma != ultimo:
            ultimo = firma
            R.diffondi(s)


# ---------------------------------------------------------------- radio (radio-browser.info)
SERVER_RADIO = ["de1", "de2", "fi1", "nl1", "at1", "all"]


def cerca_radio(parametri):
    q = urllib.parse.urlencode(dict(parametri, hidebroken="true", order="clickcount", reverse="true"))
    ultimo = None
    for s in SERVER_RADIO:
        try:
            req = urllib.request.Request("https://%s.api.radio-browser.info/json/stations/search?%s" % (s, q),
                                         headers={"User-Agent": "MassiStanze/" + VERSIONE})
            with urllib.request.urlopen(req, timeout=8) as r:
                lista = json.loads(r.read().decode("utf-8"))
            out, visti = [], set()
            for x in lista:
                url = x.get("url_resolved") or x.get("url")
                nome = (x.get("name") or "").strip()
                if not url or not nome or nome.lower() in visti:
                    continue
                visti.add(nome.lower())
                info = " · ".join(t for t in [x.get("countrycode", ""), (x.get("codec") or "").lower(),
                                              ("%s kbps" % x["bitrate"]) if x.get("bitrate") else "",
                                              ", ".join((x.get("tags") or "").split(",")[:3])] if t)
                out.append({"id": x.get("stationuuid") or secrets.token_hex(6), "nome": nome[:80], "url": url,
                            "icona": x.get("favicon") or "", "info": info[:120]})
            return out
        except Exception as e:
            ultimo = e
    raise RuntimeError("elenco radio non raggiungibile (%s)" % ultimo)


# ---------------------------------------------------------------- rete, firewall, avvio (come MassiDeck)
def ps(comando, attesa=8):
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", comando],
                           capture_output=True, text=True, timeout=attesa, creationflags=0x08000000)
        return r.stdout.strip()
    except Exception:
        return ""


_CACHE = {}


def in_cache(chiave, durata, funzione):
    v = _CACHE.get(chiave)
    if v and time.time() - v[0] < durata:
        return v[1]
    r = funzione()
    _CACHE[chiave] = (time.time(), r)
    return r


def _ip_windows():
    out = ps("Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } | "
             "ForEach-Object { $_.IPv4Address.IPAddress + '|' + $_.InterfaceAlias + '|' + $_.NetProfile.NetworkCategory }")
    righe = []
    for r in out.splitlines():
        parti = r.strip().split("|")
        if len(parti) == 3 and parti[0] and not parti[0].startswith("169.254"):
            righe.append({"ip": parti[0], "scheda": parti[1], "tipo": parti[2]})
    return righe


def reti_windows():
    return in_cache("reti", 20, _ip_windows) if WINDOWS else []


def _firewall():
    out = ps("$r = Get-NetFirewallRule -DisplayName 'MassiStanze' -ErrorAction SilentlyContinue | Where-Object { $_.Enabled -eq 'True' -and $_.Action -eq 'Allow' }; "
             "$b = @(Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue | Where-Object { $_.Program -like '*MassiStanze*' } | "
             "Get-NetFirewallRule -ErrorAction SilentlyContinue | Where-Object { $_.Enabled -eq 'True' -and $_.Action -eq 'Block' }).Count; "
             "if ($r) { 'ok|' + $b } else { 'manca|' + $b }", 15)
    parti = out.split("|")
    return {"regola": parti[0] == "ok", "blocchi": int(parti[1]) if len(parti) > 1 and parti[1].isdigit() else 0,
            "letto": bool(out)}


def stato_firewall():
    return in_cache("fw", 15, _firewall) if WINDOWS else {"regola": True, "blocchi": 0, "letto": False}


def sblocca_firewall():
    comandi = ('netsh advfirewall firewall delete rule name=all program="%s" & '
               'netsh advfirewall firewall delete rule name="MassiStanze" & '
               'netsh advfirewall firewall add rule name="MassiStanze" dir=in action=allow protocol=TCP localport=%d profile=any'
               % (sys.executable, PORTA))
    if not WINDOWS:
        log("   (prova) firewall: " + comandi)
        return True
    import ctypes
    r = ctypes.windll.shell32.ShellExecuteW(None, "runas", "cmd.exe", "/c " + comandi, None, 0)
    _CACHE.pop("fw", None)
    return r > 32


def ip_locali():
    ips = [r["ip"] for r in reti_windows()]
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    ips = list(dict.fromkeys(ips))
    if not reti_windows():
        ips.sort(key=lambda i: 0 if i.startswith(("192.168.", "10.", "172.")) else 1)
    return ips or ["127.0.0.1"]


def file_avvio():
    return os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Start Menu", "Programs", "Startup",
                        "MassiStanze.cmd")


def avvio_automatico(attivo=None):
    if not WINDOWS:
        return False
    f = file_avvio()
    if attivo is True:
        exe = sys.executable if getattr(sys, "frozen", False) else '%s" "%s' % (sys.executable, os.path.abspath(__file__))
        with open(f, "w", encoding="utf-8") as h:
            h.write('@start "" "%s" --avvio\r\n' % exe)
    elif attivo is False and os.path.exists(f):
        os.remove(f)
    return os.path.exists(f)


# ---------------------------------------------------------------- server web
TENTATIVI = {}
VISTI = set()


def e_locale(req):
    return (req.remote or "") in ("127.0.0.1", "::1", "::ffff:127.0.0.1")


def autorizzato(req, token=None):
    if e_locale(req):
        return True
    t = token or req.headers.get("X-Stanze-Token", "") or req.query.get("token", "")
    return bool(t) and t in SEGRETI.get("token", [])


def risposta(dati, codice=200):
    return web.json_response(dati, status=codice, dumps=lambda x: json.dumps(x, ensure_ascii=False),
                             headers={"Cache-Control": "no-store"})


@web.middleware
async def cors(req, handler):
    if req.method == "OPTIONS":
        resp = web.Response(status=204)
    else:
        ip = req.remote or ""
        if not e_locale(req) and ip not in VISTI:
            VISTI.add(ip)
            log("Il dispositivo %s raggiunge il PC ✓" % ip)
        try:
            resp = await handler(req)
        except web.HTTPException as e:
            resp = e
    resp.headers["Access-Control-Allow-Origin"] = "*"
    resp.headers["Access-Control-Allow-Headers"] = "Content-Type, X-Stanze-Token, X-Nome"
    resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    resp.headers["Access-Control-Allow-Private-Network"] = "true"
    return resp


async def h_ping(req):
    return risposta({"app": "MassiStanze", "versione": VERSIONE, "nome": socket.gethostname(),
                     "autorizzato": autorizzato(req)})


async def h_accedi(req):
    ip = req.remote or ""
    err, quando = TENTATIVI.get(ip, [0, 0])
    if err >= 5 and time.time() - quando < 60:
        return risposta({"errore": "Troppi tentativi: aspetta un minuto"}, 429)
    try:
        dati = await req.json()
    except Exception:
        dati = {}
    if str(dati.get("pin", "")).strip() != SEGRETI["pin"]:
        TENTATIVI[ip] = [err + 1, time.time()]
        log("PIN sbagliato da %s" % ip)
        return risposta({"errore": "PIN sbagliato"}, 403)
    TENTATIVI.pop(ip, None)
    t = secrets.token_urlsafe(24)
    SEGRETI.setdefault("token", []).append(t)
    del SEGRETI["token"][:-30]
    salva_json(FILE_SEGRETI, SEGRETI)
    log("Dispositivo collegato: %s" % ip)
    return risposta({"token": t, "nome": socket.gethostname()})


async def h_carica(req):
    """Carica un file musicale (il corpo è il file; il nome nell'intestazione X-Nome)."""
    if not autorizzato(req):
        return risposta({"errore": "PIN richiesto"}, 401)
    nome = urllib.parse.unquote(req.headers.get("X-Nome", "") or req.query.get("nome", "brano.mp3"))
    nome = os.path.basename(nome.replace("\\", "/"))[:150] or "brano.mp3"
    est = os.path.splitext(nome)[1].lower()
    if est not in ESTENSIONI:
        return risposta({"errore": "Formato non supportato: " + (est or "?")}, 400)
    bid = secrets.token_hex(5)
    file = bid + est
    percorso = os.path.join(MUSICA, file)
    tot = 0
    try:
        with open(percorso, "wb") as f:
            async for blocco in req.content.iter_chunked(1 << 16):
                tot += len(blocco)
                if tot > 400 * 1024 * 1024:
                    raise ValueError("file troppo grande (max 400 MB)")
                f.write(blocco)
        titolo, artista, durata = await asyncio.to_thread(leggi_info, percorso, nome)
    except Exception as e:
        try:
            os.remove(percorso)
        except Exception:
            pass
        return risposta({"errore": "%s: %s" % (nome, e)}, 400)
    b = {"id": bid, "file": file, "titolo": titolo, "artista": artista, "durata": durata,
         "nome_file": nome, "da": time.strftime("%Y-%m-%d %H:%M")}
    D["brani"].append(b)
    salva()
    log("♪ Caricato: %s" % titolo)
    R.diffondi(R.libreria())
    return risposta({"ok": True, "brano": b})



async def h_radio(req):
    if not autorizzato(req):
        return risposta({"errore": "PIN richiesto"}, 401)
    q = req.query.get("q", "").strip()
    par = {"name": q, "limit": "50"} if q else {"countrycode": "IT", "limit": "80"}
    try:
        lista = await asyncio.to_thread(cerca_radio, par)
        return risposta({"radio": lista})
    except Exception as e:
        return risposta({"errore": str(e)}, 502)


async def h_pc(req):
    if not e_locale(req):
        return risposta({"errore": "solo dal PC"}, 403)
    return risposta({"pin": SEGRETI["pin"], "ip": await asyncio.to_thread(ip_locali), "porta": PORTA,
                     "nome": socket.gethostname(), "windows": WINDOWS, "avvio": avvio_automatico(),
                     "registro": REGISTRO[-15:], "versione": VERSIONE, "dati": DATI,
                     "reti": await asyncio.to_thread(reti_windows),
                     "firewall": await asyncio.to_thread(stato_firewall)})


async def h_pc_azione(req):
    if not e_locale(req):
        return risposta({"errore": "solo dal PC"}, 403)
    a = req.match_info["azione"]
    try:
        dati = await req.json()
    except Exception:
        dati = {}
    if a == "nuovo-pin":
        SEGRETI["pin"] = "%04d" % random.SystemRandom().randint(0, 9999)
        SEGRETI["token"] = []
        salva_json(FILE_SEGRETI, SEGRETI)
        log("Nuovo PIN: i dispositivi devono ricollegarsi")
        for c in list(CLIENTI.values()):
            if not c.locale:
                await c.ws.close()
        return risposta({"pin": SEGRETI["pin"]})
    if a == "firewall":
        ok = await asyncio.to_thread(sblocca_firewall)
        log("Firewall: richiesta di sblocco " + ("inviata" if ok else "annullata"))
        return risposta({"ok": ok})
    if a == "avvio":
        return risposta({"avvio": avvio_automatico(bool(dati.get("attivo")))})
    if a == "chiudi":
        asyncio.get_running_loop().call_later(0.5, lambda: os._exit(0))
        return risposta({"ok": True})
    return risposta({"errore": "?"}, 404)


TIPI = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
        ".css": "text/css; charset=utf-8", ".json": "application/json", ".png": "image/png",
        ".svg": "image/svg+xml", ".ico": "image/x-icon"}


async def h_file(req):
    p = req.match_info.get("p", "") or "index.html"
    nome = os.path.normpath(urllib.parse.unquote(p).lstrip("/"))
    if nome.startswith("..") or os.path.isabs(nome):
        raise web.HTTPNotFound()
    f = os.path.join(WEB, nome)
    if not os.path.isfile(f):
        raise web.HTTPNotFound()
    with open(f, "rb") as h:
        corpo = h.read()
    return web.Response(body=corpo, content_type=TIPI.get(os.path.splitext(f)[1], "application/octet-stream").split(";")[0],
                        charset="utf-8" if "charset" in TIPI.get(os.path.splitext(f)[1], "") else None,
                        headers={"Cache-Control": "no-cache"})


# ---------------------------------------------------------------- comandi dalle pagine
def comando(c, m):
    a = m.get("a")
    tipo_fonte = (R.fonte or {}).get("tipo")
    if a == "play":
        R.play()
    elif a == "pausa":
        R.pausa()
    elif a == "succ":
        if tipo_fonte == "brano" or (tipo_fonte is None and D["coda"]):
            R.avanti(1)
    elif a == "prec":
        if tipo_fonte == "brano":
            if R.suona and R.posT[1] - (R.posT[0] - ora()) > 4:
                R.apri_brano(D["indice"])           # più di 4 s: ricomincia il brano
            else:
                R.avanti(-1)
    elif a == "vai":                          # brano i della coda
        if D["coda"]:
            R.apri_brano(int(m.get("i", 0)))
    elif a == "cerca":                        # posizione nel brano
        if tipo_fonte == "brano":
            if R.suona:
                R.apri_brano(D["indice"], float(m.get("pos", 0)))
            else:
                R.chiudi_sorgente()
                R.posT = (ora(), float(m.get("pos", 0)))
    elif a == "suona":                        # brani: sostituisce la coda e parte da "da"
        ids = [i for i in m.get("ids", []) if brano(i)]
        if ids:
            if m.get("mescola"):
                random.shuffle(ids)
            D["coda"] = ids
            R.apri_brano(int(m.get("da", 0)))
    elif a == "accoda":
        ids = [i for i in m.get("ids", []) if brano(i)]
        if m.get("dopo") and D["coda"]:
            D["coda"][D["indice"] + 1:D["indice"] + 1] = ids
        else:
            D["coda"].extend(ids)
        if (tipo_fonte is None or (tipo_fonte == "brano" and not R.suona and not R.sorgente)) and len(D["coda"]) == len(ids):
            R.fonte = {"tipo": "brano", "id": D["coda"][0]}
            D["indice"] = 0
        salva()
    elif a == "togli":
        i = int(m.get("i", -1))
        if 0 <= i < len(D["coda"]):
            corrente = i == D["indice"] and tipo_fonte == "brano"
            del D["coda"][i]
            if i < D["indice"]:
                D["indice"] -= 1
            if corrente:
                if D["coda"] and R.suona:
                    R.apri_brano(min(D["indice"], len(D["coda"]) - 1))
                else:
                    R.taglia(conserva=False)
                    R.chiudi_sorgente()
                    R.suona = False
                    R.fonte = None
                    D["indice"] = 0
        salva()
    elif a == "sposta":
        i, j = int(m.get("i", -1)), int(m.get("j", -1))
        n = len(D["coda"])
        if 0 <= i < n and 0 <= j < n:
            x = D["coda"].pop(i)
            D["coda"].insert(j, x)
            k = D["indice"]
            if k == i:
                D["indice"] = j
            elif i < k <= j:
                D["indice"] -= 1
            elif j <= k < i:
                D["indice"] += 1
        salva()
    elif a == "svuota":
        D["coda"] = []
        D["indice"] = 0
        if tipo_fonte == "brano":
            R.taglia(conserva=False)
            R.chiudi_sorgente()
            R.suona = False
            R.fonte = None
        salva()
    elif a == "ripeti":
        D["ripeti"] = {"no": "tutto", "tutto": "uno", "uno": "no"}.get(D["ripeti"], "no")
        salva()
    elif a == "casuale":
        D["casuale"] = not D["casuale"]
        salva()
    elif a == "master":
        D["master"] = max(0, min(100, int(m.get("v", 80))))
        salva()
    elif a == "radio":
        r = m.get("radio") or {}
        if str(r.get("url", "")).startswith(("http://", "https://")):
            R.apri_radio(r)
            log("📻 %s" % r.get("nome", ""))
    elif a == "prova":
        R.apri_prova()
    elif a == "stop":
        R.taglia(conserva=False)
        R.chiudi_sorgente()
        R.suona = False
        if tipo_fonte != "brano":
            R.fonte = None
    elif a == "stanza":                       # nome, volume, attivo, ritardo di una stanza
        sid = m.get("id")
        s = D["stanze"].get(sid)
        if s is not None:
            prima = s.get("attivo", True)
            for k, lim in (("volume", (0, 100)), ("ritardo", (-300, 1500))):
                if k in m:
                    s[k] = max(lim[0], min(lim[1], int(m[k])))
            if "attivo" in m:
                s["attivo"] = bool(m["attivo"])
            if "nome" in m and str(m["nome"]).strip():
                s["nome"] = str(m["nome"]).strip()[:30]
            if s.get("attivo") and not prima and sid in CLIENTI:
                recupera(CLIENTI[sid])
            salva()
    elif a == "dimentica":                    # toglie una stanza spenta dall'elenco
        sid = m.get("id")
        if sid in D["stanze"] and sid not in CLIENTI:
            del D["stanze"][sid]
            salva()
    # ---- libreria
    elif a == "rinomina":
        b = brano(m.get("id"))
        if b:
            b["titolo"] = str(m.get("titolo", b["titolo"])).strip()[:120] or b["titolo"]
            b["artista"] = str(m.get("artista", b["artista"])).strip()[:120]
            salva()
            R.diffondi(R.libreria())
    elif a == "elimina":
        b = brano(m.get("id"))
        if b:
            if (R.fonte or {}).get("id") == b["id"]:
                R.taglia(conserva=False)
                R.chiudi_sorgente()
                R.suona = False
                R.fonte = None
            D["brani"].remove(b)
            vecchia = D["coda"]
            corrente = vecchia[D["indice"]] if 0 <= D["indice"] < len(vecchia) else None
            D["coda"] = [x for x in vecchia if x != b["id"]]
            D["indice"] = D["coda"].index(corrente) if corrente in D["coda"] else 0
            for p in D["playlist"]:
                p["brani"] = [x for x in p["brani"] if x != b["id"]]
            try:
                os.remove(os.path.join(MUSICA, b["file"]))
            except Exception:
                pass
            salva()
            R.diffondi(R.libreria())
    elif a == "playlist":                     # crea / rinomina / elimina / aggiungi / togli / ordina
        op = m.get("op")
        if op == "crea":
            p = {"id": secrets.token_hex(4), "nome": str(m.get("nome", "Playlist")).strip()[:40] or "Playlist",
                 "brani": [i for i in m.get("ids", []) if brano(i)]}
            D["playlist"].append(p)
        else:
            p = next((x for x in D["playlist"] if x["id"] == m.get("id")), None)
            if not p:
                return
            if op == "rinomina":
                p["nome"] = str(m.get("nome", p["nome"])).strip()[:40] or p["nome"]
            elif op == "elimina":
                D["playlist"].remove(p)
            elif op == "aggiungi":
                p["brani"].extend(i for i in m.get("ids", []) if brano(i) and i not in p["brani"])
            elif op == "togli":
                p["brani"] = [x for x in p["brani"] if x not in m.get("ids", [])]
            elif op == "ordina":
                ids = [i for i in m.get("ids", []) if i in p["brani"]]
                if sorted(ids) == sorted(p["brani"]):
                    p["brani"] = ids
        salva()
        R.diffondi(R.libreria())
    elif a == "preferita":
        r = m.get("radio") or {}
        D["radio"] = [x for x in D["radio"] if x.get("url") != r.get("url")]
        if m.get("on") and str(r.get("url", "")).startswith(("http://", "https://")):
            D["radio"].insert(0, {"id": r.get("id") or secrets.token_hex(5), "nome": str(r.get("nome", "Radio"))[:80],
                                  "url": r["url"], "icona": r.get("icona", ""), "info": r.get("info", "")})
        salva()
        R.diffondi(R.libreria())
    R.annuncia_stato()


async def h_ws(req):
    if not autorizzato(req):
        raise web.HTTPUnauthorized()
    cid = re.sub(r"[^A-Za-z0-9_-]", "", req.query.get("id", ""))[:40] or secrets.token_hex(6)
    nome = req.query.get("nome", "").strip()[:30]
    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=4 * 1024 * 1024, compress=False)
    await ws.prepare(req)
    vecchio = CLIENTI.get(cid)
    if vecchio:
        vecchio.manda(None)
        await vecchio.ws.close()
    c = Cliente(ws, cid, nome, e_locale(req))
    CLIENTI[cid] = c
    st = D["stanze"].setdefault(cid, {"nome": nome or ("PC" if c.locale else "Stanza %d" % (len(D["stanze"]) + 1)),
                                      "volume": 80, "attivo": True, "ritardo": 0})
    if nome and nome != st["nome"] and st["nome"].startswith("Stanza "):
        st["nome"] = nome
    salva()
    scrittore = asyncio.create_task(c.scrittore())
    c.manda(json.dumps({"t": "ciao", "id": cid, "pc": socket.gethostname(), "versione": VERSIONE, "ora": ora(),
                        "rate": RATE}, ensure_ascii=False))
    c.manda(json.dumps(R.libreria(), ensure_ascii=False))
    c.manda(json.dumps({"t": "chat_tutta", "lista": D["chat"][-150:]}, ensure_ascii=False))
    R.annuncia_stato()
    recupera(c)
    log("🔊 %s collegato" % st["nome"])
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    m = json.loads(msg.data)
                except Exception:
                    continue
                t = m.get("t")
                if t == "ping":
                    c.manda(json.dumps({"t": "pong", "c": m.get("c"), "s": ora()}))
                elif t == "cmd":
                    try:
                        comando(c, m)
                    except Exception as e:
                        log("✖ comando %s: %s" % (m.get("a"), e))
                elif t == "chat":
                    testo = str(m.get("testo", "")).strip()[:1000]
                    if testo:
                        msg_chat = {"id": secrets.token_hex(5), "cid": cid, "da": st.get("nome", "?"),
                                    "testo": testo, "ora": time.time()}
                        D["chat"].append(msg_chat)
                        del D["chat"][:-300]
                        salva()
                        R.diffondi({"t": "chat", "msg": msg_chat})
                elif t == "chat_svuota":
                    D["chat"] = []
                    salva()
                    R.diffondi({"t": "chat_tutta", "lista": []})
                elif t == "annuncio":
                    if m.get("on"):
                        ok = R.inizia_annuncio(cid)
                        c.mic = ok
                        if not ok:
                            c.manda(json.dumps({"t": "avviso", "testo": "Sta già parlando un'altra stanza"}))
                    else:
                        R.fine_annuncio(cid)
                        c.mic = False
                    R.annuncia_stato()
            elif msg.type == WSMsgType.BINARY:
                d = msg.data
                if len(d) > 16 and d[0] == 2:
                    _, freq, _x = struct.unpack("<BxxxId", d[:16])
                    R.voce(cid, np.frombuffer(d[16:], dtype=np.int16), freq or RATE)
            elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                break
    finally:
        if CLIENTI.get(cid) is c:
            del CLIENTI[cid]
            if R.annuncio and R.annuncio["id"] == cid:
                R.fine_annuncio(cid)
            log("· %s scollegato" % st.get("nome", cid))
            R.annuncia_stato()
        c.manda(None)
        scrittore.cancel()
    return ws


async def all_avvio(app):
    app["compiti"] = [asyncio.create_task(produttore()), asyncio.create_task(aggiornatore())]
    if D["coda"]:
        D["indice"] = min(D["indice"], len(D["coda"]) - 1)
        R.fonte = {"tipo": "brano", "id": D["coda"][D["indice"]]}


def crea_app():
    app = web.Application(middlewares=[cors], client_max_size=450 * 1024 * 1024)
    app.router.add_get("/api/ping", h_ping)
    app.router.add_post("/api/accedi", h_accedi)
    app.router.add_post("/api/carica", h_carica)
    app.router.add_get("/api/radio", h_radio)
    app.router.add_get("/api/pc", h_pc)
    app.router.add_post("/api/pc/{azione}", h_pc_azione)
    app.router.add_get("/ws", h_ws)
    app.router.add_route("OPTIONS", "/{p:.*}", lambda r: web.Response(status=204))
    app.router.add_get("/", h_file)
    app.router.add_get("/{p:.*}", h_file)
    app.on_startup.append(all_avvio)
    return app


def gia_acceso():
    try:
        with socket.create_connection(("127.0.0.1", PORTA), timeout=0.5):
            return True
    except Exception:
        return False


def main():
    silenzioso = "--avvio" in sys.argv or "--nascosto" in sys.argv
    if gia_acceso():
        if not silenzioso:
            webbrowser.open("http://localhost:%d/" % PORTA)
        return
    threading.Thread(target=lambda: log("MassiStanze %s acceso — dal telefono apri http://%s:%d  —  PIN %s" % (
        VERSIONE, ip_locali()[0], PORTA, SEGRETI["pin"])), daemon=True).start()
    if not silenzioso:
        threading.Timer(1.0, lambda: webbrowser.open("http://localhost:%d/" % PORTA)).start()
    try:
        web.run_app(crea_app(), host="0.0.0.0", port=PORTA, print=None, access_log=None)
    except OSError as e:
        if WINDOWS:
            import ctypes
            ctypes.windll.user32.MessageBoxW(0, "La porta %d è occupata da un altro programma.\n%s" % (PORTA, e),
                                             "MassiStanze", 0x10)
        raise


if __name__ == "__main__":
    main()
