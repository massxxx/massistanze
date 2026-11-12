# Prova automatica di MassiStanze (usata da GitHub Actions su Windows).
# Uso: python ci/prova.py [indirizzo]   — il programma deve essere già acceso.
import asyncio
import io
import json
import struct
import sys
import wave

import aiohttp
import numpy as np

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8766"
RADIO = "--radio" in sys.argv


def wav_di_prova(secondi=3, freq=440):
    t = np.arange(int(44100 * secondi)) / 44100
    x = (np.sin(2 * np.pi * freq * t) * 9000).astype(np.int16)
    b = io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(44100)
        w.writeframes(x.tobytes())
    return b.getvalue()


async def main():
    async with aiohttp.ClientSession(trust_env=False) as s:
        for _ in range(60):                       # aspetta che il programma sia acceso
            try:
                p = await (await s.get(BASE + "/api/ping")).json()
                break
            except aiohttp.ClientError:
                await asyncio.sleep(1)
        assert p["app"] == "MassiStanze", p
        print("ping ok:", p)
        ids = []
        for n, f in (("prova uno.wav", 440), ("prova due.wav", 660)):
            r = await s.post(BASE + "/api/carica", data=wav_di_prova(2, f), headers={"X-Nome": n})
            j = await r.json()
            assert r.status == 200, j
            ids.append(j["brano"]["id"])
            print("caricato:", j["brano"]["titolo"], j["brano"]["durata"], "s")
        ws = await s.ws_connect(BASE.replace("http", "ws") + "/ws?id=prova&nome=Prova")
        pacchetti, stati = [], []

        async def leggi():
            async for m in ws:
                if m.type == aiohttp.WSMsgType.BINARY:
                    pacchetti.append(m.data)
                elif m.type == aiohttp.WSMsgType.TEXT:
                    j = json.loads(m.data)
                    if j["t"] == "stato":
                        stati.append(j)
        t = asyncio.create_task(leggi())
        await ws.send_json({"t": "cmd", "a": "suona", "ids": ids})
        await asyncio.sleep(5.5)
        secondi = len(pacchetti) * 0.1
        print("brani: ricevuti %.1f s di audio" % secondi)
        assert secondi >= 3.9, "audio dei brani mancante"
        a = np.frombuffer(b"".join(x[16:] for x in pacchetti), np.int16).reshape(-1, 2)[:, 0].astype(float)
        print("volume medio", round(float(np.sqrt((a ** 2).mean()))))
        assert np.sqrt((a ** 2).mean()) > 1000, "audio muto"
        await ws.send_json({"t": "chat", "testo": "prova chat"})
        if RADIO:
            r = await (await s.get(BASE + "/api/radio", timeout=aiohttp.ClientTimeout(total=40))).json()
            lista = r.get("radio") or []
            print("radio italiane trovate:", len(lista), [x["nome"] for x in lista[:6]])
            assert lista, r
            ok = False
            for radio in lista[:6]:
                pacchetti.clear()
                await ws.send_json({"t": "cmd", "a": "radio", "radio": radio})
                await asyncio.sleep(9)
                print("  %s: %.1f s di audio" % (radio["nome"], len(pacchetti) * 0.1),
                      (stati[-1].get("avviso") if stati else ""))
                if len(pacchetti) * 0.1 >= 3:
                    ok = True
                    break
            assert ok, "nessuna radio suona"
            await ws.send_json({"t": "cmd", "a": "stop"})
        t.cancel()
        await ws.close()
        print("TUTTO OK")


asyncio.run(main())
