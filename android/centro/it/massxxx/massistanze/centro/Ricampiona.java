package it.massxxx.massistanze.centro;

/** Da qualsiasi frequenza e numero di canali a 48 kHz stereo (interpolazione lineare, senza scatti tra un blocco e l'altro). */
public final class Ricampiona {
    private final int canali;
    private final double passo;
    private double pos = 0;          // posizione nel flusso d'ingresso (in campioni per canale), relativa a "ultimo"
    private float ultL = 0, ultR = 0;
    private boolean primo = true;

    public Ricampiona(int freq, int canali) {
        this.canali = Math.max(1, canali);
        this.passo = freq / 48000.0;
    }

    /** in: short intercalati con "canali" canali, n campioni totali. Restituisce stereo 48 kHz. */
    public short[] trasforma(short[] in, int n) {
        int fr = n / canali;
        if (fr == 0) return new short[0];
        float[] L = new float[fr + 1], R = new float[fr + 1];
        L[0] = ultL; R[0] = ultR;
        for (int k = 0; k < fr; k++) {
            int b = k * canali;
            if (canali == 1) { L[k + 1] = in[b]; R[k + 1] = in[b]; }
            else { L[k + 1] = in[b]; R[k + 1] = in[b + 1]; }
        }
        if (primo) { L[0] = L[1]; R[0] = R[1]; primo = false; pos = 1; }
        if (passo == 1.0 && pos == 1) {     // già a 48 kHz
            short[] out = new short[fr * 2];
            for (int k = 0; k < fr; k++) { out[2 * k] = (short) L[k + 1]; out[2 * k + 1] = (short) R[k + 1]; }
            ultL = L[fr]; ultR = R[fr];
            return out;
        }
        int quanti = (int) Math.max(0, Math.floor((fr - pos) / passo) + 1);
        short[] out = new short[quanti * 2];
        int j = 0;
        while (pos <= fr && j < quanti) {
            int a = (int) Math.floor(pos);
            double f = pos - a;
            float l = a + 1 <= fr ? (float) (L[a] + (L[a + 1] - L[a]) * f) : L[a];
            float r = a + 1 <= fr ? (float) (R[a] + (R[a + 1] - R[a]) * f) : R[a];
            out[2 * j] = (short) Math.max(-32768, Math.min(32767, l));
            out[2 * j + 1] = (short) Math.max(-32768, Math.min(32767, r));
            j++;
            pos += passo;
        }
        pos -= fr;
        ultL = L[fr]; ultR = R[fr];
        if (j * 2 < out.length) { short[] o2 = new short[j * 2]; System.arraycopy(out, 0, o2, 0, j * 2); return o2; }
        return out;
    }
}
