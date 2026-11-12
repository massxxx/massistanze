package it.massxxx.massistanze.centro;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Piccolo server HTTP + WebSocket per il centro (stesse richieste del programma per PC). */
public final class Server {
    public interface Risorse {
        /** contenuto del file della pagina (es. "index.html"), null se non c'è */
        byte[] leggi(String percorso);
    }

    public interface Azioni {
        List<String> indirizzi();
        void chiudi();
    }

    final Centro c;
    final Risorse ris;
    final Azioni az;
    final int porta;
    private ServerSocket ss;
    private volatile boolean acceso = false;
    private final Map<String, long[]> tentativi = new HashMap<>();
    private final Set<String> visti = new HashSet<>();

    public Server(Centro c, Risorse ris, int porta, Azioni az) {
        this.c = c;
        this.ris = ris;
        this.porta = porta;
        this.az = az;
    }

    public void avvia() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(porta));
        acceso = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (acceso) {
                    try {
                        final Socket s = ss.accept();
                        Thread h = new Thread(new Runnable() { public void run() { connessione(s); } }, "http");
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        if (!acceso) return;
                    }
                }
            }
        }, "server");
        t.setDaemon(true);
        t.start();
        c.log("MassiStanze " + Centro.VERSIONE + " acceso sulla porta " + porta + " — PIN " + c.segreti.get("pin"));
    }

    public void ferma() {
        acceso = false;
        try { ss.close(); } catch (Exception e) { /* niente */ }
    }

    // ---------------------------------------------------------------- richieste
    static final class Richiesta {
        String metodo, percorso;
        Map<String, String> query = new HashMap<>(), testate = new HashMap<>();
        String ip;
        boolean locale;
    }

    static String riga(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int x;
        while ((x = in.read()) >= 0) {
            if (x == '\n') break;
            if (x != '\r') b.write(x);
            if (b.size() > 16384) throw new IOException("riga troppo lunga");
        }
        if (x < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private void connessione(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(120000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            InetAddress ia = s.getInetAddress();
            while (true) {
                String prima = riga(in);
                if (prima == null || prima.isEmpty()) break;
                String[] p = prima.split(" ");
                if (p.length < 2) break;
                Richiesta r = new Richiesta();
                r.metodo = p[0].toUpperCase(Locale.ROOT);
                String u = p[1];
                int q = u.indexOf('?');
                r.percorso = dec(q >= 0 ? u.substring(0, q) : u);
                if (q >= 0) for (String kv : u.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) r.query.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
                    else if (!kv.isEmpty()) r.query.put(dec(kv), "");
                }
                String h;
                while ((h = riga(in)) != null && !h.isEmpty()) {
                    int d = h.indexOf(':');
                    if (d > 0) r.testate.put(h.substring(0, d).trim().toLowerCase(Locale.ROOT), h.substring(d + 1).trim());
                }
                r.ip = ia.getHostAddress();
                r.locale = ia.isLoopbackAddress();
                if (!r.locale) {
                    synchronized (visti) {
                        if (visti.add(r.ip)) c.log("Il dispositivo " + r.ip + " raggiunge il centro ✓");
                    }
                }
                if ("websocket".equalsIgnoreCase(r.testate.get("upgrade")) && "/ws".equals(r.percorso)) {
                    websocket(r, s, in, out);
                    return;
                }
                boolean resta = gestisci(r, in, out);
                out.flush();
                if (!resta || "close".equalsIgnoreCase(r.testate.get("connection"))) break;
            }
        } catch (Exception e) {
            // connessione chiusa
        } finally {
            try { s.close(); } catch (Exception e) { /* niente */ }
        }
    }

    private void rispondi(OutputStream out, int codice, String tipo, byte[] corpo) throws IOException {
        String stato = codice == 200 ? "OK" : codice == 204 ? "No Content" : codice == 401 ? "Unauthorized" : codice == 403 ? "Forbidden"
                : codice == 404 ? "Not Found" : codice == 429 ? "Too Many Requests" : "Error";
        StringBuilder t = new StringBuilder();
        t.append("HTTP/1.1 ").append(codice).append(' ').append(stato).append("\r\n");
        if (tipo != null) t.append("Content-Type: ").append(tipo).append("\r\n");
        t.append("Content-Length: ").append(corpo == null ? 0 : corpo.length).append("\r\n");
        t.append("Cache-Control: no-cache\r\n");
        t.append("Access-Control-Allow-Origin: *\r\n");
        t.append("Access-Control-Allow-Headers: Content-Type, X-Stanze-Token, X-Nome\r\n");
        t.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        t.append("Access-Control-Allow-Private-Network: true\r\n");
        t.append("Connection: keep-alive\r\n\r\n");
        out.write(t.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (corpo != null) out.write(corpo);
    }

    private void json(OutputStream out, int codice, Object dati) throws IOException {
        rispondi(out, codice, "application/json; charset=utf-8", Json.scrivi(dati).getBytes(StandardCharsets.UTF_8));
    }

    private boolean autorizzato(Richiesta r) {
        if (r.locale) return true;
        String t = r.testate.get("x-stanze-token");
        if (t == null || t.isEmpty()) t = r.query.get("token");
        return t != null && !t.isEmpty() && Json.lista(c.segreti.get("token")).contains(t);
    }

    private byte[] corpo(Richiesta r, InputStream in, int max) throws IOException {
        long n = (long) Json.num(new HashMap<String, Object>(r.testate), "content-length", 0);
        if (n > max) throw new IOException("troppo grande");
        byte[] b = new byte[(int) n];
        int f = 0;
        while (f < n) {
            int x = in.read(b, f, (int) n - f);
            if (x < 0) throw new IOException("fine");
            f += x;
        }
        return b;
    }

    static final Map<String, String> TIPI = new HashMap<>();
    static {
        TIPI.put("html", "text/html; charset=utf-8");
        TIPI.put("js", "text/javascript; charset=utf-8");
        TIPI.put("css", "text/css; charset=utf-8");
        TIPI.put("json", "application/json");
        TIPI.put("png", "image/png");
        TIPI.put("svg", "image/svg+xml");
        TIPI.put("ico", "image/x-icon");
    }

    /** @return true se la connessione può restare aperta */
    private boolean gestisci(Richiesta r, InputStream in, OutputStream out) throws Exception {
        String p = r.percorso;
        if ("OPTIONS".equals(r.metodo)) { rispondi(out, 204, null, null); return true; }
        if ("/api/ping".equals(p)) {
            json(out, 200, Json.m("app", "MassiStanze", "versione", Centro.VERSIONE, "nome", c.nomeDispositivo, "autorizzato", autorizzato(r)));
            return true;
        }
        if ("/api/accedi".equals(p) && "POST".equals(r.metodo)) {
            Map<String, Object> d = Json.oggetto(new String(corpo(r, in, 10000), StandardCharsets.UTF_8));
            synchronized (tentativi) {
                long[] t = tentativi.get(r.ip);
                if (t != null && t[0] >= 5 && System.currentTimeMillis() - t[1] < 60000) {
                    json(out, 429, Json.m("errore", "Troppi tentativi: aspetta un minuto"));
                    return true;
                }
                if (!Json.str(d, "pin", "").trim().equals(c.segreti.get("pin"))) {
                    tentativi.put(r.ip, new long[]{(t == null ? 0 : t[0]) + 1, System.currentTimeMillis()});
                    c.log("PIN sbagliato da " + r.ip);
                    json(out, 403, Json.m("errore", "PIN sbagliato"));
                    return true;
                }
                tentativi.remove(r.ip);
            }
            String tok = c.esad(18);
            synchronized (c) {
                List<Object> tt = Json.lista(c.segreti.get("token"));
                tt.add(tok);
                while (tt.size() > 30) tt.remove(0);
                Centro.scriviFile(c.fileSegreti, Json.scrivi(c.segreti));
            }
            c.log("Dispositivo collegato: " + r.ip);
            json(out, 200, Json.m("token", tok, "nome", c.nomeDispositivo));
            return true;
        }
        if (p.startsWith("/api/")) {
            if (!autorizzato(r)) {
                if ("POST".equals(r.metodo)) return false;          // non leggiamo il corpo: chiudiamo
                json(out, 401, Json.m("errore", "PIN richiesto"));
                return true;
            }
            if ("/api/carica".equals(p) && "POST".equals(r.metodo)) return carica(r, in, out);
            if ("/api/radio".equals(p)) {
                try {
                    json(out, 200, Json.m("radio", c.cercaRadio(r.query.containsKey("q") ? r.query.get("q").trim() : "")));
                } catch (Exception e) {
                    json(out, 502, Json.m("errore", e.getMessage()));
                }
                return true;
            }
            if (!r.locale) { json(out, 403, Json.m("errore", "solo dal centro")); return true; }
            if ("/api/pc".equals(p)) {
                List<String> reg;
                synchronized (c.registro) { reg = new ArrayList<>(c.registro.subList(Math.max(0, c.registro.size() - 15), c.registro.size())); }
                json(out, 200, Json.m("pin", c.segreti.get("pin"), "ip", az.indirizzi(), "porta", (long) porta, "nome", c.nomeDispositivo,
                        "windows", false, "telefono", true, "avvio", false, "registro", reg, "versione", Centro.VERSIONE, "dati", c.dati.getPath(),
                        "reti", new ArrayList<Object>(), "firewall", Json.m("regola", true, "blocchi", 0L, "letto", false)));
                return true;
            }
            if (p.startsWith("/api/pc/")) {
                if (r.testate.containsKey("content-length")) corpo(r, in, 10000);
                String a = p.substring(8);
                if ("nuovo-pin".equals(a)) {
                    synchronized (c) {
                        c.segreti.put("pin", String.format(Locale.ROOT, "%04d", c.caso.nextInt(10000)));
                        c.segreti.put("token", new ArrayList<Object>());
                        Centro.scriviFile(c.fileSegreti, Json.scrivi(c.segreti));
                    }
                    c.log("Nuovo PIN: i dispositivi devono ricollegarsi");
                    for (Cliente x : c.clienti.values()) if (!x.locale) x.chiudi();
                    json(out, 200, Json.m("pin", c.segreti.get("pin")));
                } else if ("chiudi".equals(a)) {
                    json(out, 200, Json.m("ok", true));
                    out.flush();
                    new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(400); } catch (InterruptedException e) { /* niente */ }
                            az.chiudi();
                        }
                    }).start();
                } else json(out, 200, Json.m("ok", false, "errore", "non serve sul telefono"));
                return true;
            }
            json(out, 404, Json.m("errore", "?"));
            return true;
        }
        // file della pagina
        if (p.isEmpty() || "/".equals(p)) p = "/index.html";
        String nome = p.substring(1);
        if (nome.contains("..")) { json(out, 404, Json.m("errore", "?")); return true; }
        byte[] b = ris.leggi(nome);
        if (b == null) { json(out, 404, Json.m("errore", "file non trovato")); return true; }
        if ("index.html".equals(nome)) {      // gli altri telefoni usano la pagina come sito, non come app
            String h = new String(b, StandardCharsets.UTF_8).replace("window.STANZE_APP = true;", "");
            b = h.getBytes(StandardCharsets.UTF_8);
        }
        String est = nome.contains(".") ? nome.substring(nome.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        rispondi(out, 200, TIPI.containsKey(est) ? TIPI.get(est) : "application/octet-stream", b);
        return true;
    }

    private boolean carica(Richiesta r, InputStream in, OutputStream out) throws Exception {
        String nome = dec(r.testate.containsKey("x-nome") ? r.testate.get("x-nome") : r.query.containsKey("nome") ? r.query.get("nome") : "brano.mp3");
        nome = nome.replace('\\', '/');
        nome = nome.substring(nome.lastIndexOf('/') + 1);
        if (nome.length() > 150) nome = nome.substring(0, 150);
        if (nome.isEmpty()) nome = "brano.mp3";
        String est = nome.contains(".") ? nome.substring(nome.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        boolean chunked = "chunked".equalsIgnoreCase(r.testate.get("transfer-encoding"));
        long n = (long) Json.num(new HashMap<String, Object>(r.testate), "content-length", -1);
        if (!Centro.estensioneValida(est)) {
            json(out, 400, Json.m("errore", "Formato non supportato: " + (est.isEmpty() ? "?" : est)));
            return false;
        }
        if (n > 400L * 1024 * 1024) { json(out, 400, Json.m("errore", "file troppo grande (max 400 MB)")); return false; }
        File f = new File(c.musica, c.esad(5) + est);
        try {
            try (FileOutputStream o = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                if (chunked) {
                    while (true) {
                        String l = riga(in);
                        int k = Integer.parseInt(l.split(";")[0].trim(), 16);
                        if (k == 0) { riga(in); break; }
                        while (k > 0) {
                            int x = in.read(buf, 0, Math.min(buf.length, k));
                            if (x < 0) throw new IOException("fine");
                            o.write(buf, 0, x);
                            k -= x;
                        }
                        riga(in);
                    }
                } else {
                    long resta = Math.max(0, n);
                    while (resta > 0) {
                        int x = in.read(buf, 0, (int) Math.min(buf.length, resta));
                        if (x < 0) throw new IOException("fine");
                        o.write(buf, 0, x);
                        resta -= x;
                    }
                }
            }
            Map<String, Object> b = c.nuovoBrano(f, nome);
            json(out, 200, Json.m("ok", true, "brano", b));
        } catch (Exception e) {
            f.delete();
            json(out, 400, Json.m("errore", nome + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
        return true;
    }

    static String base64(byte[] b) {            // java.util.Base64 non c'è sui telefoni vecchi
        String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 255) << 16 | (i + 1 < b.length ? (b[i + 1] & 255) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 255 : 0);
            s.append(A.charAt(n >> 18 & 63)).append(A.charAt(n >> 12 & 63));
            s.append(i + 1 < b.length ? A.charAt(n >> 6 & 63) : '=').append(i + 2 < b.length ? A.charAt(n & 63) : '=');
        }
        return s.toString();
    }

    private void websocket(Richiesta r, Socket s, InputStream in, OutputStream out) throws Exception {
        if (!autorizzato(r)) {
            rispondi(out, 401, null, null);
            out.flush();
            return;
        }
        String chiave = r.testate.get("sec-websocket-key");
        String acc = base64(MessageDigest.getInstance("SHA-1").digest(
                (chiave + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        s.setSoTimeout(0);
        String id = r.query.containsKey("id") ? r.query.get("id").replaceAll("[^A-Za-z0-9_-]", "") : "";
        if (id.length() > 40) id = id.substring(0, 40);
        if (id.isEmpty()) id = c.esad(6);
        String nome = r.query.containsKey("nome") ? r.query.get("nome").trim() : "";
        if (nome.length() > 30) nome = nome.substring(0, 30);
        final WebSocket ws = new WebSocket(s, in, out);
        final Cliente cl = new Cliente(id, r.locale, ws);
        c.entra(cl, nome);
        try {
            ws.ascolta(new WebSocket.Ascolto() {
                public void testo(String t) { c.testo(cl, t); }
                public void binario(byte[] b) { c.binario(cl, b); }
            });
        } finally {
            cl.chiudi();
            c.esce(cl);
        }
    }
}
