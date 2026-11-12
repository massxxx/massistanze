package it.massxxx.massistanze.centro;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Il "centro" di MassiStanze (stessa logica di pc/massistanze.py): libreria, coda, radio,
 * stanze, chat, annunci; produce i pacchetti audio con l'ora in cui suonarli e li manda a tutti.
 */
public final class Centro {
    public static final String VERSIONE = "1.1";
    static final int RATE = 48000, PEZZO = 4800;
    static final double DUR = PEZZO / (double) RATE;
    static final double ANTICIPO = 1.0, ANTICIPO_VOCE = 0.40, TAGLIO = 0.30, ABBASSA = 0.25;
    static final String[] ESTENSIONI = {".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma", ".mp4", ".webm", ".3gp", ".amr"};

    final File dati, musica, fileStato, fileSegreti;
    final Decodificatore dec;
    final String nomeDispositivo;
    final long t0 = System.nanoTime();
    final SecureRandom caso = new SecureRandom();
    final Random rnd = new Random();

    Map<String, Object> D;
    Map<String, Object> segreti;
    final Map<String, Cliente> clienti = new ConcurrentHashMap<>();
    final List<String> registro = Collections.synchronizedList(new ArrayList<String>());

    // regia
    Sorgente sorgente;
    Map<String, Object> fonte;
    boolean suona = false;
    double prossimo = 0;
    final ArrayDeque<Pacchetto> storia = new ArrayDeque<>();
    final ArrayDeque<Pacchetto> rimetti = new ArrayDeque<>();
    double posT0 = 0, posT1 = 0;
    Annuncio annuncio;
    String avviso = "";

    final ScheduledExecutorService orologio = Executors.newScheduledThreadPool(1);
    ScheduledFuture<?> salvaPresto;
    String ultimaFirma = "";

    static final class Pacchetto {
        double T, pos;
        short[] musica;
        float[] voce;
        String chi;
        final Map<String, byte[]> mix = new HashMap<>();
    }

    static final class Annuncio {
        String id;
        float[] mic = new float[0];
        boolean pronto, fine;
    }

    public Centro(File dati, Decodificatore dec, String nomeDispositivo) {
        this.dati = dati;
        this.dec = dec;
        this.nomeDispositivo = nomeDispositivo;
        musica = new File(dati, "musica");
        musica.mkdirs();
        fileStato = new File(dati, "stato.json");
        fileSegreti = new File(dati, "accesso.json");
        D = Json.oggetto(leggiFile(fileStato, "{}"));
        for (String k : new String[]{"brani", "playlist", "radio", "coda", "chat"}) if (!(D.get(k) instanceof List)) D.put(k, new ArrayList<Object>());
        if (!(D.get("stanze") instanceof Map)) D.put("stanze", new LinkedHashMap<String, Object>());
        if (!D.containsKey("indice")) D.put("indice", 0L);
        if (!D.containsKey("ripeti")) D.put("ripeti", "no");
        if (!D.containsKey("casuale")) D.put("casuale", false);
        if (!D.containsKey("master")) D.put("master", 80L);
        Iterator<Object> it = brani().iterator();
        while (it.hasNext()) if (!new File(musica, Json.str(Json.mappa(it.next()), "file", "?")).isFile()) it.remove();
        segreti = Json.oggetto(leggiFile(fileSegreti, "{}"));
        if (!(segreti.get("pin") instanceof String)) {
            segreti = Json.m("pin", String.format(Locale.ROOT, "%04d", caso.nextInt(10000)), "token", new ArrayList<Object>());
            scriviFile(fileSegreti, Json.scrivi(segreti));
        }
        List<Object> coda = coda();
        if (!coda.isEmpty()) {
            int i = Math.min(indice(), coda.size() - 1);
            D.put("indice", (long) i);
            fonte = Json.m("tipo", "brano", "id", coda.get(i));
        }
        orologio.scheduleAtFixedRate(new Runnable() {
            public void run() {
                try { giro(); } catch (Throwable e) { log("✖ errore audio: " + e); }
            }
        }, 20, 20, TimeUnit.MILLISECONDS);
        orologio.scheduleAtFixedRate(new Runnable() {
            public void run() {
                try { aggiornatore(); } catch (Throwable e) { /* niente */ }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public void spegni() {
        orologio.shutdownNow();
        synchronized (this) {
            chiudiSorgente();
            salvaOra();
        }
        for (Cliente c : clienti.values()) c.chiudi();
    }

    // ---------------------------------------------------------------- utilità
    public double ora() { return (System.nanoTime() - t0) / 1e9; }

    public void log(String msg) {
        String riga = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date()) + "  " + msg;
        synchronized (registro) {
            registro.add(riga);
            while (registro.size() > 40) registro.remove(0);
        }
        System.out.println(riga);
    }

    static String leggiFile(File f, String def) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return def;
        }
    }

    static void scriviFile(File f, String testo) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(testo.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    synchronized void salva() {
        if (salvaPresto != null) salvaPresto.cancel(false);
        try {
            salvaPresto = orologio.schedule(new Runnable() { public void run() { salvaOra(); } }, 800, TimeUnit.MILLISECONDS);
        } catch (Exception e) { /* spento */ }
    }

    synchronized void salvaOra() { scriviFile(fileStato, Json.scrivi(D)); }

    String esad(int byte_) {
        byte[] b = new byte[byte_];
        caso.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    List<Object> brani() { return Json.lista(D.get("brani")); }
    List<Object> coda() { return Json.lista(D.get("coda")); }
    List<Object> playlist() { return Json.lista(D.get("playlist")); }
    Map<String, Object> stanze() { return Json.mappa(D.get("stanze")); }
    int indice() { return (int) Json.num(D, "indice", 0); }
    void indice(int i) { D.put("indice", (long) i); }

    Map<String, Object> brano(Object id) {
        if (id == null) return null;
        for (Object o : brani()) {
            Map<String, Object> b = Json.mappa(o);
            if (id.equals(b.get("id"))) return b;
        }
        return null;
    }

    String tipoFonte() { return fonte == null ? null : Json.str(fonte, "tipo", null); }

    // ---------------------------------------------------------------- stato per le pagine
    synchronized Map<String, Object> stato() {
        Map<String, Object> f = fonte == null ? null : new LinkedHashMap<>(fonte);
        if (f != null && "brano".equals(f.get("tipo"))) {
            Map<String, Object> b = brano(f.get("id"));
            if (b == null) b = new LinkedHashMap<>();
            f.put("titolo", Json.str(b, "titolo", "?"));
            f.put("artista", Json.str(b, "artista", ""));
            f.put("durata", Json.num(b, "durata", 0));
        }
        if (f != null && "radio".equals(f.get("tipo")) && sorgente != null) {
            f.put("in_onda", sorgente.titoloRadio);
            f.put("carica", !sorgente.pronta);
        }
        List<Map<String, Object>> st = new ArrayList<>();
        for (Map.Entry<String, Object> e : stanze().entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>(Json.mappa(e.getValue()));
            Cliente c = clienti.get(e.getKey());
            s.put("id", e.getKey());
            s.put("online", c != null);
            s.put("mic", c != null && c.mic);
            st.add(s);
        }
        Collections.sort(st, (a, b) -> {
            boolean oa = Boolean.TRUE.equals(a.get("online")), ob = Boolean.TRUE.equals(b.get("online"));
            if (oa != ob) return oa ? -1 : 1;
            return Json.str(a, "nome", "").compareToIgnoreCase(Json.str(b, "nome", ""));
        });
        return Json.m("t", "stato", "suona", suona, "fonte", f, "posT", posT0, "pos", posT1, "coda", coda(),
                "indice", (long) indice(), "ripeti", D.get("ripeti"), "casuale", D.get("casuale"), "master", D.get("master"),
                "stanze", st, "annuncio", annuncio == null ? null : annuncio.id, "avviso", avviso);
    }

    synchronized Map<String, Object> libreria() {
        return Json.m("t", "libreria", "brani", brani(), "playlist", playlist(), "radio", D.get("radio"));
    }

    void diffondi(Map<String, Object> msg) {
        String s = Json.scrivi(msg);
        for (Cliente c : clienti.values()) c.mandaTesto(s);
    }

    void annunciaStato() { diffondi(stato()); }

    void aggiornatore() {
        Map<String, Object> s = stato();
        String firma = Json.scrivi(Json.m("f", s.get("fonte"), "s", s.get("suona")));
        if (!firma.equals(ultimaFirma)) {
            ultimaFirma = firma;
            diffondi(s);
        }
    }

    // ---------------------------------------------------------------- taglio e comandi
    synchronized List<Pacchetto> taglia(boolean conserva) {
        double da = ora() + TAGLIO;
        List<Pacchetto> tolti = new ArrayList<>();
        while (!storia.isEmpty() && storia.peekLast().T >= da) tolti.add(0, storia.pollLast());
        if (!tolti.isEmpty()) da = tolti.get(0).T;
        if (conserva) {
            for (int i = tolti.size() - 1; i >= 0; i--) if (tolti.get(i).musica != null) rimetti.addFirst(tolti.get(i));
        } else rimetti.clear();
        prossimo = Math.max(da, ora() + TAGLIO);
        diffondi(Json.m("t", "taglia", "da", da));
        return tolti;
    }

    void chiudiSorgente() {
        if (sorgente != null) sorgente.chiudi();
        sorgente = null;
        rimetti.clear();
    }

    void apriBrano(int i, double inizio, boolean tagliare) {
        if (tagliare) taglia(false);
        chiudiSorgente();
        avviso = "";
        List<Object> coda = coda();
        if (coda.isEmpty()) { fonte = null; suona = false; return; }
        i = ((i % coda.size()) + coda.size()) % coda.size();
        indice(i);
        Map<String, Object> b = brano(coda.get(i));
        if (b == null) { coda.remove(i); apriBrano(i, 0, false); return; }
        sorgente = new Sorgente(dec, new File(musica, Json.str(b, "file", "")).getPath(), inizio, false, false);
        fonte = Json.m("tipo", "brano", "id", b.get("id"));
        suona = true;
        salva();
    }

    void apriRadio(Map<String, Object> r) {
        taglia(false);
        chiudiSorgente();
        avviso = "";
        sorgente = new Sorgente(dec, Json.str(r, "url", ""), 0, true, false);
        fonte = Json.m("tipo", "radio", "nome", Json.str(r, "nome", "Radio"), "url", Json.str(r, "url", ""),
                "icona", Json.str(r, "icona", ""), "info", Json.str(r, "info", ""));
        suona = true;
    }

    void apriProva() {
        taglia(false);
        chiudiSorgente();
        sorgente = new Sorgente(dec, null, 0, false, true);
        fonte = Json.m("tipo", "prova", "titolo", "Prova sincronia", "artista", "un bip ogni secondo");
        suona = true;
    }

    void ferma() {
        chiudiSorgente();
        suona = false;
    }

    void avanti(int salto, boolean daSolo) {
        String tf = tipoFonte();
        List<Object> coda = coda();
        if (coda.isEmpty() || (tf != null && !"brano".equals(tf))) {
            if (daSolo) { ferma(); fonte = null; }
            return;
        }
        int n = coda.size(), i = indice(), nuovo;
        if (daSolo && "uno".equals(D.get("ripeti"))) nuovo = i;
        else if (Json.bool(D, "casuale") && n > 1 && salto > 0) {
            do { nuovo = rnd.nextInt(n); } while (nuovo == i);
        } else nuovo = i + salto;
        if (nuovo >= n || nuovo < 0) {
            if ("tutto".equals(D.get("ripeti")) || !daSolo) nuovo = ((nuovo % n) + n) % n;
            else {
                ferma();
                indice(0);
                fonte = Json.m("tipo", "brano", "id", coda.get(0));
                posT0 = ora();
                posT1 = 0;
                return;
            }
        }
        apriBrano(nuovo, 0, !daSolo);
    }

    void play() {
        if (suona) return;
        if ("radio".equals(tipoFonte())) { apriRadio(fonte); return; }
        if (sorgente != null && !sorgente.esaurita()) {
            suona = true;
            prossimo = Math.max(prossimo, ora() + TAGLIO);
            return;
        }
        if (!coda().isEmpty()) apriBrano(indice(), "brano".equals(tipoFonte()) ? posT1 : 0, true);
    }

    void pausa() {
        if (!suona) return;
        List<Pacchetto> tolti = taglia(true);
        if (!tolti.isEmpty()) { posT0 = tolti.get(0).T; posT1 = tolti.get(0).pos; }
        suona = false;
        if ("radio".equals(tipoFonte())) chiudiSorgente();
    }

    // ---------------------------------------------------------------- produzione dei pacchetti
    private Object[] prendiMusica() {
        if (!rimetti.isEmpty()) {
            Pacchetto h = rimetti.pollFirst();
            return new Object[]{h.musica, h.pos};
        }
        Sorgente s = sorgente;
        if (s == null) return new Object[]{null, 0.0};
        double pos = s.pos();
        short[] m = s.prendi(PEZZO);
        if (m != null) return new Object[]{m, pos};
        if (s.esaurita()) {
            if (s.errore != null) {
                avviso = "Non riesco a suonare: " + (s.errore.length() > 120 ? s.errore.substring(0, 120) : s.errore);
                log("✖ " + avviso);
            }
            if ("brano".equals(tipoFonte())) {
                avanti(1, true);
                annunciaStato();
                return new Object[]{"ancora", 0.0};
            }
            ferma();
            annunciaStato();
        }
        return new Object[]{null, 0.0};
    }

    private float[] prendiVoce() {
        Annuncio a = annuncio;
        if (a == null) return null;
        if (!a.pronto) {
            if (a.mic.length >= (int) (0.15 * RATE) || a.fine) a.pronto = true;
            else return new float[PEZZO];
        }
        float[] v = new float[PEZZO];
        int q = Math.min(PEZZO, a.mic.length);
        System.arraycopy(a.mic, 0, v, 0, q);
        float[] resto = new float[a.mic.length - q];
        System.arraycopy(a.mic, q, resto, 0, resto.length);
        a.mic = resto;
        if (q < PEZZO && a.fine && a.mic.length == 0) {
            annuncio = null;
            log("🎤 Annuncio finito");
            annunciaStato();
        }
        return v;
    }

    synchronized void giro() {
        double adesso = ora();
        if (!(suona || annuncio != null)) return;
        double anticipo = annuncio != null ? ANTICIPO_VOCE : ANTICIPO;
        if (prossimo < adesso + 0.2) prossimo = adesso + 0.25;
        int giri = 0;
        while (prossimo < adesso + anticipo && giri < 40) {
            giri++;
            short[] mus = null;
            double pos = 0;
            if (suona) {
                Object[] r = prendiMusica();
                if ("ancora".equals(r[0])) continue;
                mus = (short[]) r[0];
                pos = (Double) r[1];
            }
            if (mus == null && annuncio == null) break;
            Pacchetto h = new Pacchetto();
            h.T = prossimo;
            h.musica = mus;
            String chi = annuncio != null ? annuncio.id : null;
            h.voce = prendiVoce();
            h.chi = h.voce != null ? chi : null;
            h.pos = pos;
            storia.addLast(h);
            while (!storia.isEmpty() && storia.peekFirst().T < adesso - 3) storia.pollFirst();
            if (mus != null) { posT0 = prossimo; posT1 = pos; }
            for (Cliente c : clienti.values()) {
                byte[] b = pacchetto(h, c);
                if (b != null) c.mandaAudio(b);
            }
            prossimo += DUR;
        }
    }

    String variante(Pacchetto h, Cliente c) {
        Map<String, Object> st = Json.mappa(stanze().get(c.id));
        boolean attivo = !Boolean.FALSE.equals(st.get("attivo"));
        if (h.chi != null) {
            if (c.id.equals(h.chi)) return attivo && h.musica != null ? "senza_voce" : null;
            return attivo ? "tutto" : "solo_voce";
        }
        if (h.musica == null) return null;
        return attivo ? "tutto" : null;
    }

    byte[] pacchetto(Pacchetto h, Cliente c) {
        String v = variante(h, c);
        if (v == null) return null;
        byte[] b = h.mix.get(v);
        if (b != null) return b;
        ByteBuffer bb = ByteBuffer.allocate(16 + PEZZO * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 1).put((byte) 0).put((byte) 0).put((byte) 0).putInt(0).putDouble(h.T);
        for (int i = 0; i < PEZZO; i++) {
            float l = 0, r = 0;
            if (h.musica != null && !"solo_voce".equals(v)) {
                float g = h.chi != null ? (float) ABBASSA : 1f;
                l = h.musica[2 * i] * g;
                r = h.musica[2 * i + 1] * g;
            }
            if (!"senza_voce".equals(v) && h.voce != null) { l += h.voce[i]; r += h.voce[i]; }
            bb.putShort((short) Math.max(-32768, Math.min(32767, l)));
            bb.putShort((short) Math.max(-32768, Math.min(32767, r)));
        }
        b = bb.array();
        h.mix.put(v, b);
        return b;
    }

    synchronized void recupera(Cliente c) {
        double lim = ora() + 0.15;
        for (Pacchetto h : storia) {
            if (h.T > lim) {
                byte[] b = pacchetto(h, c);
                if (b != null) c.mandaAudio(b);
            }
        }
    }

    // ---------------------------------------------------------------- annunci
    synchronized boolean iniziaAnnuncio(String cid) {
        if (annuncio != null && !annuncio.id.equals(cid)) return false;
        annuncio = new Annuncio();
        annuncio.id = cid;
        taglia(true);
        log("🎤 Annuncio da " + Json.str(Json.mappa(stanze().get(cid)), "nome", "?"));
        return true;
    }

    synchronized void voce(String cid, short[] campioni, int freq) {
        Annuncio a = annuncio;
        if (a == null || !a.id.equals(cid) || a.fine || campioni.length == 0) return;
        float[] x;
        if (freq != RATE && campioni.length > 1) {
            int n = (int) Math.round(campioni.length * (double) RATE / freq);
            x = new float[n];
            double passo = (campioni.length - 1) / (double) Math.max(1, n - 1);
            for (int i = 0; i < n; i++) {
                double p = i * passo;
                int k = (int) p;
                double f = p - k;
                x[i] = (float) (k + 1 < campioni.length ? campioni[k] + (campioni[k + 1] - campioni[k]) * f : campioni[k]);
            }
        } else {
            x = new float[campioni.length];
            for (int i = 0; i < x.length; i++) x[i] = campioni[i];
        }
        float[] m = new float[a.mic.length + x.length];
        System.arraycopy(a.mic, 0, m, 0, a.mic.length);
        System.arraycopy(x, 0, m, a.mic.length, x.length);
        if (m.length > RATE * 3) m = java.util.Arrays.copyOfRange(m, m.length - RATE * 3, m.length);
        a.mic = m;
    }

    synchronized void fineAnnuncio(String cid) {
        if (annuncio != null && annuncio.id.equals(cid)) {
            annuncio.fine = true;
            annuncio.pronto = true;
        }
    }

    // ---------------------------------------------------------------- comandi dalle pagine
    static int intero(Map<String, Object> m, String k, int def) { return (int) Math.round(Json.num(m, k, def)); }

    List<Object> idValidi(Object lista) {
        List<Object> out = new ArrayList<>();
        for (Object o : Json.lista(lista)) if (brano(o) != null) out.add(o);
        return out;
    }

    synchronized void comando(Cliente c, Map<String, Object> m) {
        String a = Json.str(m, "a", "");
        String tf = tipoFonte();
        List<Object> coda = coda();
        switch (a) {
            case "play": play(); break;
            case "pausa": pausa(); break;
            case "succ":
                if ("brano".equals(tf) || (tf == null && !coda.isEmpty())) avanti(1, false);
                break;
            case "prec":
                if ("brano".equals(tf)) {
                    if (suona && posT1 - (posT0 - ora()) > 4) apriBrano(indice(), 0, true);
                    else avanti(-1, false);
                }
                break;
            case "vai":
                if (!coda.isEmpty()) apriBrano(intero(m, "i", 0), 0, true);
                break;
            case "cerca":
                if ("brano".equals(tf)) {
                    double p = Json.num(m, "pos", 0);
                    if (suona) apriBrano(indice(), p, true);
                    else { chiudiSorgente(); posT0 = ora(); posT1 = p; }
                }
                break;
            case "suona": {
                List<Object> ids = idValidi(m.get("ids"));
                if (!ids.isEmpty()) {
                    if (Json.bool(m, "mescola")) Collections.shuffle(ids);
                    D.put("coda", ids);
                    apriBrano(intero(m, "da", 0), 0, true);
                }
                break;
            }
            case "accoda": {
                List<Object> ids = idValidi(m.get("ids"));
                if (Json.bool(m, "dopo") && !coda.isEmpty()) coda.addAll(Math.min(indice() + 1, coda.size()), ids);
                else coda.addAll(ids);
                if ((tf == null || ("brano".equals(tf) && !suona && sorgente == null)) && coda.size() == ids.size() && !ids.isEmpty()) {
                    fonte = Json.m("tipo", "brano", "id", coda.get(0));
                    indice(0);
                }
                salva();
                break;
            }
            case "togli": {
                int i = intero(m, "i", -1);
                if (i >= 0 && i < coda.size()) {
                    boolean corrente = i == indice() && "brano".equals(tf);
                    coda.remove(i);
                    if (i < indice()) indice(indice() - 1);
                    if (corrente) {
                        if (!coda.isEmpty() && suona) apriBrano(Math.min(indice(), coda.size() - 1), 0, true);
                        else { taglia(false); ferma(); fonte = null; indice(0); }
                    }
                }
                salva();
                break;
            }
            case "sposta": {
                int i = intero(m, "i", -1), j = intero(m, "j", -1), n = coda.size();
                if (i >= 0 && i < n && j >= 0 && j < n) {
                    Object x = coda.remove(i);
                    coda.add(j, x);
                    int k = indice();
                    if (k == i) indice(j);
                    else if (i < k && k <= j) indice(k - 1);
                    else if (j <= k && k < i) indice(k + 1);
                }
                salva();
                break;
            }
            case "svuota":
                coda.clear();
                indice(0);
                if ("brano".equals(tf)) { taglia(false); ferma(); fonte = null; }
                salva();
                break;
            case "ripeti": {
                Object r = D.get("ripeti");
                D.put("ripeti", "no".equals(r) ? "tutto" : "tutto".equals(r) ? "uno" : "no");
                salva();
                break;
            }
            case "casuale": D.put("casuale", !Json.bool(D, "casuale")); salva(); break;
            case "master": D.put("master", (long) Math.max(0, Math.min(100, intero(m, "v", 80)))); salva(); break;
            case "radio": {
                Map<String, Object> r = Json.mappa(m.get("radio"));
                String u = Json.str(r, "url", "");
                if (u.startsWith("http://") || u.startsWith("https://")) { apriRadio(r); log("📻 " + Json.str(r, "nome", "")); }
                break;
            }
            case "prova": apriProva(); break;
            case "stop":
                taglia(false);
                ferma();
                if (!"brano".equals(tf)) fonte = null;
                break;
            case "stanza": {
                String sid = Json.str(m, "id", "");
                Map<String, Object> s = stanze().containsKey(sid) ? Json.mappa(stanze().get(sid)) : null;
                if (s != null) {
                    boolean prima = !Boolean.FALSE.equals(s.get("attivo"));
                    if (m.containsKey("volume")) s.put("volume", (long) Math.max(0, Math.min(100, intero(m, "volume", 80))));
                    if (m.containsKey("ritardo")) s.put("ritardo", (long) Math.max(-300, Math.min(1500, intero(m, "ritardo", 0))));
                    if (m.containsKey("attivo")) s.put("attivo", Json.bool(m, "attivo"));
                    String nome = Json.str(m, "nome", "").trim();
                    if (!nome.isEmpty()) s.put("nome", nome.length() > 30 ? nome.substring(0, 30) : nome);
                    if (!Boolean.FALSE.equals(s.get("attivo")) && !prima && clienti.containsKey(sid)) recupera(clienti.get(sid));
                    salva();
                }
                break;
            }
            case "dimentica": {
                String sid = Json.str(m, "id", "");
                if (stanze().containsKey(sid) && !clienti.containsKey(sid)) { stanze().remove(sid); salva(); }
                break;
            }
            case "rinomina": {
                Map<String, Object> b = brano(m.get("id"));
                if (b != null) {
                    String t = Json.str(m, "titolo", "").trim();
                    if (!t.isEmpty()) b.put("titolo", taglio(t, 120));
                    b.put("artista", taglio(Json.str(m, "artista", Json.str(b, "artista", "")).trim(), 120));
                    salva();
                    diffondi(libreria());
                }
                break;
            }
            case "elimina": {
                Map<String, Object> b = brano(m.get("id"));
                if (b != null) {
                    Object id = b.get("id");
                    if (fonte != null && id.equals(fonte.get("id"))) { taglia(false); ferma(); fonte = null; }
                    brani().remove(b);
                    Object corr = indice() < coda.size() ? coda.get(indice()) : null;
                    List<Object> nuova = new ArrayList<>();
                    for (Object x : coda) if (!id.equals(x)) nuova.add(x);
                    D.put("coda", nuova);
                    indice(corr != null && nuova.contains(corr) ? nuova.indexOf(corr) : 0);
                    for (Object po : playlist()) Json.lista(Json.mappa(po).get("brani")).remove(id);
                    new File(musica, Json.str(b, "file", "?")).delete();
                    salva();
                    diffondi(libreria());
                }
                break;
            }
            case "playlist": {
                String op = Json.str(m, "op", "");
                if ("crea".equals(op)) {
                    String nome = Json.str(m, "nome", "Playlist").trim();
                    playlist().add(Json.m("id", esad(4), "nome", nome.isEmpty() ? "Playlist" : taglio(nome, 40), "brani", idValidi(m.get("ids"))));
                } else {
                    Map<String, Object> p = null;
                    for (Object o : playlist()) if (Json.str(Json.mappa(o), "id", "").equals(Json.str(m, "id", "-"))) p = Json.mappa(o);
                    if (p == null) break;
                    List<Object> pb = Json.lista(p.get("brani"));
                    if ("rinomina".equals(op)) {
                        String nome = Json.str(m, "nome", "").trim();
                        if (!nome.isEmpty()) p.put("nome", taglio(nome, 40));
                    } else if ("elimina".equals(op)) playlist().remove(p);
                    else if ("aggiungi".equals(op)) {
                        for (Object x : idValidi(m.get("ids"))) if (!pb.contains(x)) pb.add(x);
                    } else if ("togli".equals(op)) pb.removeAll(Json.lista(m.get("ids")));
                    else if ("ordina".equals(op)) {
                        List<Object> ids = new ArrayList<>();
                        for (Object x : Json.lista(m.get("ids"))) if (pb.contains(x)) ids.add(x);
                        if (new HashSet<>(ids).equals(new HashSet<>(pb)) && ids.size() == pb.size()) p.put("brani", ids);
                    }
                }
                salva();
                diffondi(libreria());
                break;
            }
            case "preferita": {
                Map<String, Object> r = Json.mappa(m.get("radio"));
                String u = Json.str(r, "url", "");
                List<Object> pref = Json.lista(D.get("radio"));
                Iterator<Object> it = pref.iterator();
                while (it.hasNext()) if (u.equals(Json.str(Json.mappa(it.next()), "url", ""))) it.remove();
                if (Json.bool(m, "on") && (u.startsWith("http://") || u.startsWith("https://")))
                    pref.add(0, Json.m("id", Json.str(r, "id", esad(5)), "nome", taglio(Json.str(r, "nome", "Radio"), 80), "url", u,
                            "icona", Json.str(r, "icona", ""), "info", Json.str(r, "info", "")));
                salva();
                diffondi(libreria());
                break;
            }
            default:
                break;
        }
        annunciaStato();
    }

    static String unisci(String sep, List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String x : l) { if (b.length() > 0) b.append(sep); b.append(x); }
        return b.toString();
    }

    static String taglio(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    // ---------------------------------------------------------------- chat
    synchronized void chat(Cliente c, String testo) {
        testo = taglio(testo.trim(), 1000);
        if (testo.isEmpty()) return;
        Map<String, Object> msg = Json.m("id", esad(5), "cid", c.id, "da", Json.str(Json.mappa(stanze().get(c.id)), "nome", "?"),
                "testo", testo, "ora", System.currentTimeMillis() / 1000.0);
        List<Object> ch = Json.lista(D.get("chat"));
        ch.add(msg);
        while (ch.size() > 300) ch.remove(0);
        salva();
        diffondi(Json.m("t", "chat", "msg", msg));
    }

    synchronized void chatSvuota() {
        D.put("chat", new ArrayList<Object>());
        salva();
        diffondi(Json.m("t", "chat_tutta", "lista", new ArrayList<Object>()));
    }

    // ---------------------------------------------------------------- dispositivi collegati
    synchronized Map<String, Object> entra(Cliente c, String nome) {
        Cliente vecchio = clienti.put(c.id, c);
        if (vecchio != null) vecchio.chiudi();
        Map<String, Object> st = stanze().containsKey(c.id) ? Json.mappa(stanze().get(c.id)) : null;
        if (st == null) {
            st = Json.m("nome", !nome.isEmpty() ? nome : (c.locale ? "Telefono centro" : "Stanza " + (stanze().size() + 1)),
                    "volume", 80L, "attivo", true, "ritardo", 0L);
            stanze().put(c.id, st);
        } else if (!nome.isEmpty() && Json.str(st, "nome", "").startsWith("Stanza ")) st.put("nome", nome);
        salva();
        c.mandaTesto(Json.scrivi(Json.m("t", "ciao", "id", c.id, "pc", nomeDispositivo, "versione", VERSIONE, "ora", ora(), "rate", (long) RATE)));
        c.mandaTesto(Json.scrivi(libreria()));
        List<Object> ch = Json.lista(D.get("chat"));
        c.mandaTesto(Json.scrivi(Json.m("t", "chat_tutta", "lista", new ArrayList<>(ch.subList(Math.max(0, ch.size() - 150), ch.size())))));
        annunciaStato();
        recupera(c);
        log("🔊 " + Json.str(st, "nome", "?") + " collegato");
        return st;
    }

    synchronized void esce(Cliente c) {
        if (clienti.get(c.id) == c) {
            clienti.remove(c.id);
            if (annuncio != null && annuncio.id.equals(c.id)) fineAnnuncio(c.id);
            log("· " + Json.str(Json.mappa(stanze().get(c.id)), "nome", c.id) + " scollegato");
            annunciaStato();
        }
    }

    /** messaggi di testo da una pagina */
    void testo(Cliente c, String data) {
        Map<String, Object> m;
        try { m = Json.oggetto(data); } catch (Exception e) { return; }
        String t = Json.str(m, "t", "");
        try {
            switch (t) {
                case "ping":
                    c.mandaTesto(Json.scrivi(Json.m("t", "pong", "c", m.get("c"), "s", ora())));
                    break;
                case "cmd": comando(c, m); break;
                case "chat": chat(c, Json.str(m, "testo", "")); break;
                case "chat_svuota": chatSvuota(); break;
                case "annuncio":
                    synchronized (this) {
                        if (Json.bool(m, "on")) {
                            boolean ok = iniziaAnnuncio(c.id);
                            c.mic = ok;
                            if (!ok) c.mandaTesto(Json.scrivi(Json.m("t", "avviso", "testo", "Sta già parlando un'altra stanza")));
                        } else {
                            fineAnnuncio(c.id);
                            c.mic = false;
                        }
                        annunciaStato();
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log("✖ comando " + Json.str(m, "a", t) + ": " + e);
        }
    }

    /** pacchetti binari (voce del microfono) */
    void binario(Cliente c, byte[] d) {
        if (d.length <= 16 || d[0] != 2) return;
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int freq = bb.getInt(4);
        short[] x = new short[(d.length - 16) / 2];
        bb.position(16);
        bb.asShortBuffer().get(x);
        voce(c.id, x, freq > 0 ? freq : RATE);
    }

    // ---------------------------------------------------------------- caricamento brani
    public static boolean estensioneValida(String est) {
        for (String e : ESTENSIONI) if (e.equals(est)) return true;
        return false;
    }

    Map<String, Object> nuovoBrano(File f, String nomeFile) throws Exception {
        Object[] info = dec.info(f.getPath(), nomeFile);
        String titolo = taglio(String.valueOf(info[0]).trim(), 120), artista = taglio(String.valueOf(info[1]).trim(), 120);
        if (titolo.isEmpty()) titolo = nomeFile.replaceAll("\\.[^.]*$", "");
        if (artista.isEmpty() && titolo.contains(" - ")) {
            String[] p = titolo.split(" - ", 2);
            artista = p[0].trim();
            titolo = p[1].trim();
        }
        Map<String, Object> b = Json.m("id", f.getName().replaceAll("\\..*$", ""), "file", f.getName(), "titolo", titolo,
                "artista", artista, "durata", Math.round(((Number) info[2]).doubleValue() * 10) / 10.0, "nome_file", nomeFile,
                "da", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALY).format(new Date()));
        synchronized (this) {
            brani().add(b);
            salva();
        }
        log("♪ Caricato: " + titolo);
        diffondi(libreria());
        return b;
    }

    // ---------------------------------------------------------------- radio
    static final String[] SERVER_RADIO = {"de1", "de2", "fi1", "nl1", "at1", "all"};

    List<Object> cercaRadio(String q) throws Exception {
        String par = q.isEmpty() ? "countrycode=IT&limit=80" : "name=" + URLEncoder.encode(q, "UTF-8") + "&limit=50";
        par += "&hidebroken=true&order=clickcount&reverse=true";
        Exception ultimo = null;
        for (String s : SERVER_RADIO) {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("https://" + s + ".api.radio-browser.info/json/stations/search?" + par).openConnection();
                h.setRequestProperty("User-Agent", "MassiStanze/" + VERSIONE);
                h.setConnectTimeout(8000);
                h.setReadTimeout(10000);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                try (InputStream in = h.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
                }
                List<Object> out = new ArrayList<>();
                Set<String> visti = new HashSet<>();
                for (Object o : Json.lista(Json.leggi(new String(b.toByteArray(), StandardCharsets.UTF_8)))) {
                    Map<String, Object> x = Json.mappa(o);
                    String url = Json.str(x, "url_resolved", "");
                    if (url.isEmpty()) url = Json.str(x, "url", "");
                    String nome = Json.str(x, "name", "").trim();
                    if (url.isEmpty() || nome.isEmpty() || !visti.add(nome.toLowerCase(Locale.ROOT))) continue;
                    List<String> info = new ArrayList<>();
                    for (String k : new String[]{Json.str(x, "countrycode", ""), Json.str(x, "codec", "").toLowerCase(Locale.ROOT),
                            Json.num(x, "bitrate", 0) > 0 ? (long) Json.num(x, "bitrate", 0) + " kbps" : ""}) if (!k.isEmpty()) info.add(k);
                    String[] tag = Json.str(x, "tags", "").split(",");
                    List<String> tt = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, tag.length); i++) if (!tag[i].isEmpty()) tt.add(tag[i]);
                    if (!tt.isEmpty()) info.add(unisci(", ", tt));
                    out.add(Json.m("id", Json.str(x, "stationuuid", esad(6)), "nome", taglio(nome, 80), "url", url,
                            "icona", Json.str(x, "favicon", ""), "info", taglio(unisci(" · ", info), 120)));
                }
                return out;
            } catch (Exception e) {
                ultimo = e;
            }
        }
        throw new Exception("elenco radio non raggiungibile (" + ultimo + ")");
    }
}
