package it.massxxx.massistanze.centro;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** WebSocket lato server (RFC 6455), senza estensioni. */
public final class WebSocket {
    public interface Ascolto {
        void testo(String s);
        void binario(byte[] b);
    }

    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private volatile boolean chiuso = false;

    WebSocket(Socket sock, InputStream in, OutputStream out) {
        this.sock = sock;
        this.in = in;
        this.out = out;
    }

    void mandaTesto(String s) throws IOException { cornice(0x1, s.getBytes(StandardCharsets.UTF_8)); }

    void mandaBinario(byte[] b) throws IOException { cornice(0x2, b); }

    void ping() throws IOException { cornice(0x9, new byte[0]); }

    private synchronized void cornice(int op, byte[] dati) throws IOException {
        if (chiuso) throw new IOException("chiuso");
        int n = dati.length;
        byte[] testa;
        if (n < 126) testa = new byte[]{(byte) (0x80 | op), (byte) n};
        else if (n < 65536) testa = new byte[]{(byte) (0x80 | op), 126, (byte) (n >> 8), (byte) n};
        else {
            testa = new byte[10];
            testa[0] = (byte) (0x80 | op);
            testa[1] = 127;
            for (int i = 0; i < 8; i++) testa[9 - i] = (byte) ((long) n >> (8 * i));
        }
        out.write(testa);
        out.write(dati);
        out.flush();
    }

    void chiudi() {
        if (chiuso) return;
        try { cornice(0x8, new byte[0]); } catch (Exception e) { /* niente */ }
        chiuso = true;
        try { sock.close(); } catch (Exception e) { /* niente */ }
    }

    private int leggiByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("fine");
        return b;
    }

    private void leggiTutto(byte[] b) throws IOException {
        int f = 0;
        while (f < b.length) {
            int r = in.read(b, f, b.length - f);
            if (r < 0) throw new IOException("fine");
            f += r;
        }
    }

    /** legge i messaggi finché la connessione resta aperta */
    void ascolta(Ascolto a) {
        java.io.ByteArrayOutputStream pezzi = new java.io.ByteArrayOutputStream();
        int opPezzi = 0;
        try {
            while (!chiuso) {
                int b0 = leggiByte(), b1 = leggiByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                long n = b1 & 0x7f;
                if (n == 126) n = (leggiByte() << 8) | leggiByte();
                else if (n == 127) { n = 0; for (int i = 0; i < 8; i++) n = (n << 8) | leggiByte(); }
                if (n > 8 * 1024 * 1024) throw new IOException("messaggio troppo grande");
                byte[] maschera = new byte[4];
                if ((b1 & 0x80) != 0) leggiTutto(maschera);
                byte[] dati = new byte[(int) n];
                leggiTutto(dati);
                if ((b1 & 0x80) != 0) for (int i = 0; i < dati.length; i++) dati[i] ^= maschera[i & 3];
                if (op == 0x8) break;
                if (op == 0x9) { cornice(0xA, dati); continue; }
                if (op == 0xA) continue;
                if (op == 0x0) { pezzi.write(dati); if (!fin) continue; dati = pezzi.toByteArray(); pezzi.reset(); op = opPezzi; }
                else if (!fin) { opPezzi = op; pezzi.reset(); pezzi.write(dati); continue; }
                if (op == 0x1) a.testo(new String(dati, StandardCharsets.UTF_8));
                else if (op == 0x2) a.binario(dati);
            }
        } catch (IOException e) {
            // connessione chiusa
        } finally {
            chiudi();
        }
    }
}
