package it.massxxx.massistanze.centro;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Solo per le prove sul computer: decodifica con ffmpeg, pagina dalla cartella web/. */
public final class Desktop {
    public static void main(String[] a) throws Exception {
        final File web = new File(a.length > 0 ? a[0] : "web");
        File dati = new File(a.length > 1 ? a[1] : "/tmp/centro-java");
        int porta = a.length > 2 ? Integer.parseInt(a[2]) : 8766;
        Decodificatore dec = new Decodificatore() {
            public void decodifica(String url, double inizio, boolean radio, Uscita out) throws Exception {
                List<String> cmd = new ArrayList<>();
                cmd.add("ffmpeg"); cmd.add("-loglevel"); cmd.add("error");
                if (inizio > 0) { cmd.add("-ss"); cmd.add(String.valueOf(inizio)); }
                cmd.add("-i"); cmd.add(url); cmd.add("-f"); cmd.add("s16le"); cmd.add("-ac"); cmd.add("2"); cmd.add("-ar"); cmd.add("48000"); cmd.add("-");
                Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[19200];
                    int f = 0, r;
                    while (!out.fermata() && (r = in.read(buf, f, buf.length - f)) > 0) {
                        f += r;
                        if (f == buf.length) {
                            short[] s = new short[f / 2];
                            ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
                            out.metti(s, s.length);
                            f = 0;
                        }
                    }
                    if (f >= 4 && !out.fermata()) {
                        short[] s = new short[(f / 4) * 2];
                        ByteBuffer.wrap(buf, 0, s.length * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
                        out.metti(s, s.length);
                    }
                } finally {
                    p.destroy();
                }
                if (p.waitFor() != 0 && !out.fermata()) throw new Exception("ffmpeg non riesce a leggere il file");
            }

            public Object[] info(String percorso, String nomeFile) throws Exception {
                Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration:format_tags=title,artist",
                        "-of", "default=nw=1", percorso).start();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                p.getInputStream().transferTo(b);
                if (p.waitFor() != 0) throw new Exception("nel file non c'è audio");
                String t = "", ar = "";
                double d = 0;
                for (String l : b.toString("UTF-8").split("\n")) {
                    if (l.startsWith("duration=")) try { d = Double.parseDouble(l.substring(9)); } catch (Exception e) { /* niente */ }
                    if (l.toLowerCase().startsWith("tag:title=")) t = l.substring(10);
                    if (l.toLowerCase().startsWith("tag:artist=")) ar = l.substring(11);
                }
                return new Object[]{t, ar, d};
            }
        };
        final Centro c = new Centro(dati, dec, "telefono-prova");
        Server s = new Server(c, new Server.Risorse() {
            public byte[] leggi(String percorso) {
                try { File f = new File(web, percorso); return f.isFile() ? Files.readAllBytes(f.toPath()) : null; } catch (Exception e) { return null; }
            }
        }, porta, new Server.Azioni() {
            public List<String> indirizzi() { List<String> l = new ArrayList<>(); l.add("192.0.2.2"); return l; }
            public void chiudi() { c.spegni(); System.exit(0); }
        });
        s.avvia();
        Thread.currentThread().join();
    }
}
