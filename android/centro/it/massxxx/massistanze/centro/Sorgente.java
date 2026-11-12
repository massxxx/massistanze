package it.massxxx.massistanze.centro;

import java.util.ArrayDeque;

/** Decodifica in un thread e tiene un serbatoio di PCM pronto (come la classe Sorgente del programma per PC). */
public final class Sorgente implements Decodificatore.Uscita {
    public static final int RATE = 48000;
    final String url;
    final double inizio;
    final boolean radio, prova;
    private final ArrayDeque<short[]> pezzi = new ArrayDeque<>();
    private int primoUsato = 0;          // campioni (short) già presi dal primo pezzo
    private long n = 0;                  // frame (stereo) nel serbatoio
    long consumati = 0;
    volatile boolean finita = false, ferma = false, pronta;
    volatile String errore = null;
    volatile double durata = 0;
    volatile String titoloRadio = "";

    public Sorgente(final Decodificatore dec, String url, double inizio, boolean radio, boolean prova) {
        this.url = url;
        this.inizio = Math.max(0, inizio);
        this.radio = radio;
        this.prova = prova;
        this.pronta = !radio;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    if (Sorgente.this.prova) prova();
                    else dec.decodifica(Sorgente.this.url, Sorgente.this.inizio, Sorgente.this.radio, Sorgente.this);
                } catch (Throwable e) {
                    errore = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                } finally {
                    finita = true;
                    pronta = true;
                }
            }
        }, "sorgente");
        t.setDaemon(true);
        t.start();
    }

    public void chiudi() { ferma = true; }

    public double pos() { return inizio + consumati / (double) RATE; }

    public synchronized boolean esaurita() { return finita && n == 0; }

    // ---- Uscita
    public boolean fermata() { return ferma; }

    public void durata(double s) { durata = s; }

    public void titolo(String t) { titoloRadio = t == null ? "" : t.trim(); }

    public void metti(short[] pcm, int quanti) {
        if (quanti <= 0) return;
        short[] a = quanti == pcm.length ? pcm : java.util.Arrays.copyOf(pcm, quanti);
        while (!ferma && !radio) {
            synchronized (this) { if (n <= 8L * RATE) break; }
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
        }
        synchronized (this) {
            pezzi.addLast(a);
            n += a.length / 2;
            if (radio) {
                while (n > 12L * RATE && pezzi.size() > 1) {    // la radio corre più del nostro orologio: butta il vecchio
                    short[] v = pezzi.pollFirst();
                    n -= (v.length - primoUsato) / 2;
                    primoUsato = 0;
                }
                if (!pronta && n >= (long) (1.5 * RATE)) pronta = true;
            }
        }
    }

    private void prova() {
        short[] bip = new short[2400];
        for (int i = 0; i < 2400; i++) {
            double h = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / 2399);
            bip[i] = (short) (Math.sin(2 * Math.PI * 1000 * i / (double) RATE) * 12000 * h);
        }
        for (int k = 0; k < 600 && !ferma; k++) {
            for (int j = 0; j < 10; j++) {
                short[] a = new short[4800 * 2];
                if (j == 0) for (int i = 0; i < 2400; i++) { a[2 * i] = bip[i]; a[2 * i + 1] = bip[i]; }
                metti(a, a.length);
            }
        }
    }

    /** k frame stereo, oppure null se non ce ne sono abbastanza (buco). */
    public synchronized short[] prendi(int k) {
        if (!pronta) return null;
        if (n < k) {
            if (finita && n > 0) {
                short[] out = new short[k * 2];
                int j = 0;
                while (!pezzi.isEmpty()) {
                    short[] p = pezzi.pollFirst();
                    int da = primoUsato;
                    primoUsato = 0;
                    int q = p.length - da;
                    System.arraycopy(p, da, out, j, q);
                    j += q;
                }
                consumati += n;
                n = 0;
                return out;
            }
            if (radio && !finita) pronta = false;          // buco nella radio: riempi di nuovo
            return null;
        }
        short[] out = new short[k * 2];
        int j = 0, serve = k * 2;
        while (serve > 0) {
            short[] p = pezzi.peekFirst();
            int disp = p.length - primoUsato;
            int q = Math.min(disp, serve);
            System.arraycopy(p, primoUsato, out, j, q);
            j += q;
            serve -= q;
            primoUsato += q;
            if (primoUsato >= p.length) { pezzi.pollFirst(); primoUsato = 0; }
        }
        n -= k;
        consumati += k;
        return out;
    }
}
