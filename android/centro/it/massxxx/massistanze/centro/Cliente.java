package it.massxxx.massistanze.centro;

import java.util.concurrent.ArrayBlockingQueue;

/** Un dispositivo collegato con il WebSocket: un thread scrive i messaggi in ordine. */
public final class Cliente {
    final String id;
    final boolean locale;
    volatile boolean mic = false;
    private final WebSocket ws;
    private final ArrayBlockingQueue<Object> coda = new ArrayBlockingQueue<>(80);
    private volatile boolean chiuso = false;
    private static final Object FINE = new Object();

    Cliente(String id, boolean locale, WebSocket ws) {
        this.id = id;
        this.locale = locale;
        this.ws = ws;
        Thread t = new Thread(new Runnable() {
            public void run() { scrittore(); }
        }, "cliente-" + id);
        t.setDaemon(true);
        t.start();
    }

    void mandaTesto(String s) { if (!chiuso) coda.offer(s); }

    void mandaAudio(byte[] b) { if (!chiuso) coda.offer(b); }       // coda piena: il telefono è lento, salta il pacchetto

    void chiudi() {
        chiuso = true;
        coda.clear();
        coda.offer(FINE);
        ws.chiudi();
    }

    private void scrittore() {
        try {
            while (true) {
                Object o = coda.poll(15, java.util.concurrent.TimeUnit.SECONDS);
                if (o == null) { ws.ping(); continue; }       // tiene viva la connessione
                if (o == FINE) return;
                if (o instanceof String) ws.mandaTesto((String) o);
                else ws.mandaBinario((byte[]) o);
            }
        } catch (Exception e) {
            chiuso = true;
            ws.chiudi();
        }
    }
}
