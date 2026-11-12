package it.massxxx.massistanze;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import it.massxxx.massistanze.centro.Decodificatore;
import it.massxxx.massistanze.centro.Ricampiona;

/** Decodifica con i decodificatori di Android (MediaExtractor + MediaCodec). */
public final class DecoderAndroid implements Decodificatore {
    private final Context ctx;

    public DecoderAndroid(Context ctx) { this.ctx = ctx; }

    private static void apri(MediaExtractor ex, String url) throws Exception {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "MassiStanze/1.1");
            ex.setDataSource(url, h);
        } else ex.setDataSource(url);
    }

    @Override
    public void decodifica(String url, double inizio, boolean radio, Uscita out) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            apri(ex, url);
            int traccia = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { traccia = i; fmt = f; break; }
            }
            if (traccia < 0) throw new Exception("nel file non c'è audio");
            ex.selectTrack(traccia);
            if (!radio && fmt.containsKey(MediaFormat.KEY_DURATION)) out.durata(fmt.getLong(MediaFormat.KEY_DURATION) / 1e6);
            long saltaUs = (long) (inizio * 1e6);
            if (saltaUs > 0) ex.seekTo(saltaUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int freq = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int canali = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean virgola = false;
            Ricampiona ric = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean fineIn = false, fineOut = false;
            short[] tmp = new short[0];
            while (!fineOut && !out.fermata()) {
                if (!fineIn) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        ByteBuffer b = codec.getInputBuffer(i);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fineIn = true;
                        } else {
                            codec.queueInputBuffer(i, 0, n, Math.max(0, ex.getSampleTime()), 0);
                            ex.advance();
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f2 = codec.getOutputFormat();
                    if (f2.containsKey(MediaFormat.KEY_SAMPLE_RATE)) freq = f2.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (f2.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) canali = f2.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    virgola = f2.containsKey("pcm-encoding") && f2.getInteger("pcm-encoding") == 4;   // ENCODING_PCM_FLOAT
                    ric = null;
                } else if (o >= 0) {
                    if (info.size > 0 && (saltaUs <= 0 || info.presentationTimeUs >= saltaUs - 30000)) {
                        ByteBuffer b = codec.getOutputBuffer(o);
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        b.order(ByteOrder.nativeOrder());
                        int n;
                        if (virgola) {
                            n = info.size / 4;
                            if (tmp.length < n) tmp = new short[n];
                            for (int k = 0; k < n; k++) tmp[k] = (short) Math.max(-32768, Math.min(32767, b.getFloat() * 32767));
                        } else {
                            n = info.size / 2;
                            if (tmp.length < n) tmp = new short[n];
                            b.asShortBuffer().get(tmp, 0, n);
                        }
                        if (ric == null) ric = new Ricampiona(freq, canali);
                        short[] s = ric.trasforma(tmp, n);
                        if (s.length > 0) out.metti(s, s.length);
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) fineOut = true;
                }
            }
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception e) { /* niente */ }
                try { codec.release(); } catch (Exception e) { /* niente */ }
            }
            ex.release();
        }
    }

    @Override
    public Object[] info(String percorso, String nomeFile) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        boolean audio = false;
        double durata = 0;
        try {
            ex.setDataSource(percorso);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audio = true;
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durata = f.getLong(MediaFormat.KEY_DURATION) / 1e6;
                    break;
                }
            }
        } finally {
            ex.release();
        }
        if (!audio) throw new Exception("nel file non c'è audio");
        String titolo = "", artista = "";
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(percorso);
            String t = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (t != null) titolo = t;
            if (a != null) artista = a;
            if (d != null && durata == 0) durata = Long.parseLong(d) / 1000.0;
        } catch (Exception e) {
            // niente metadati
        } finally {
            try { m.release(); } catch (Exception e) { /* niente */ }
        }
        return new Object[]{titolo, artista, durata};
    }
}
