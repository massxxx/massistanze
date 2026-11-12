package it.massxxx.massistanze.centro;

/** Trasforma un file o una radio in PCM 48 kHz stereo (short intercalati). Android usa MediaCodec. */
public interface Decodificatore {
    interface Uscita {
        /** campioni stereo intercalati a 48 kHz; blocca se il serbatoio è pieno */
        void metti(short[] pcm, int n);
        boolean fermata();
        void durata(double secondi);
        void titolo(String t);
    }

    void decodifica(String url, double inizio, boolean radio, Uscita out) throws Exception;

    /** {titolo, artista, durata in secondi}; eccezione se nel file non c'è audio */
    Object[] info(String percorso, String nomeFile) throws Exception;
}
