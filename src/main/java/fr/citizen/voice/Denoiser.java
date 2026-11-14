package fr.citizen.voice;

/**
 * Réducteur de bruit parasite (ventilateur, souffle, bourdonnement, bruit de fond), sans dépendance.
 * Soustraction spectrale : le bruit de fond est mesuré en continu, fréquence par fréquence, puis retiré
 * du micro. La voix passe, le bruit constant disparaît. Filtre coupe-bas en plus (grondements < 90 Hz).
 * PCM 16 kHz mono, trames de taille quelconque, 16 ms de retard.
 */
public final class Denoiser {
    private static final int N = 256;       // taille d'analyse (16 ms)
    private static final int H = N / 2;     // pas (50 % de recouvrement)
    private static final int BINS = N / 2 + 1;
    private static final float FLOOR = 0.08f;   // atténuation max du bruit (~ -22 dB)
    private static final float OVERSUB = 2.0f;  // soustraction renforcée pour éviter les résidus

    private final float[] window = new float[N];
    private final float[] inBuf = new float[N];
    private int inFill = H; // les H premiers échantillons sont du silence initial
    private final float[] olaBuf = new float[N];
    private final float[] noise = new float[BINS];
    private final float[] gainPrev = new float[BINS];
    private final float[] smooth = new float[BINS];
    private final float[] re = new float[N];
    private final float[] im = new float[N];
    private int frames;

    private final java.util.ArrayDeque<Float> out = new java.util.ArrayDeque<>();

    // coupe-bas (biquad 90 Hz)
    private float hx1, hx2, hy1, hy2;
    private final float b0, b1, b2, a1, a2;

    public Denoiser() {
        for (int i = 0; i < N; i++) {
            window[i] = (float) Math.sqrt(0.5 * (1 - Math.cos(2 * Math.PI * i / N))); // racine de Hann
        }
        java.util.Arrays.fill(gainPrev, 1f);
        // initialise la sortie avec H zéros (latence fixe)
        for (int i = 0; i < H; i++) {
            out.add(0f);
        }
        double w0 = 2 * Math.PI * 90 / 16000.0;
        double alpha = Math.sin(w0) / (2 * 0.707);
        double cos = Math.cos(w0);
        double a0 = 1 + alpha;
        b0 = (float) ((1 + cos) / 2 / a0);
        b1 = (float) (-(1 + cos) / a0);
        b2 = b0;
        a1 = (float) (-2 * cos / a0);
        a2 = (float) ((1 - alpha) / a0);
    }

    /** Traite une trame et renvoie une trame de même taille, débruitée. */
    public short[] process(short[] pcm) {
        for (short s : pcm) {
            float x = s;
            float y = b0 * x + b1 * hx1 + b2 * hx2 - a1 * hy1 - a2 * hy2;
            hx2 = hx1;
            hx1 = x;
            hy2 = hy1;
            hy1 = y;
            inBuf[inFill++] = y;
            if (inFill == N) {
                block();
                System.arraycopy(inBuf, H, inBuf, 0, H);
                inFill = H;
            }
        }
        short[] res = new short[pcm.length];
        for (int i = 0; i < res.length; i++) {
            Float v = out.poll();
            float f = v == null ? 0 : v;
            res[i] = (short) Math.max(-32768, Math.min(32767, Math.round(f)));
        }
        return res;
    }

    private void block() {
        for (int i = 0; i < N; i++) {
            re[i] = inBuf[i] * window[i];
            im[i] = 0;
        }
        fft(re, im, false);
        frames++;
        for (int k = 0; k < BINS; k++) {
            float p = re[k] * re[k] + im[k] * im[k];
            // puissance lissée (réduit les fluctuations aléatoires du bruit)
            smooth[k] = frames == 1 ? p : 0.6f * smooth[k] + 0.4f * p;
            float ps = smooth[k];
            // Estimation du bruit : suit vite les baisses, remonte lentement (la voix ne compte presque pas)
            if (frames <= 15) {
                noise[k] = frames == 1 ? ps : noise[k] + (ps - noise[k]) / frames;
            } else if (ps < noise[k]) {
                noise[k] = 0.7f * noise[k] + 0.3f * ps;
            } else {
                noise[k] = Math.min(ps, noise[k] * 1.02f + 1e-3f);
            }
            float g = ps > 0 ? 1f - OVERSUB * noise[k] / ps : 0f;
            g = Math.max(FLOOR, g);
            // lissage dans le temps : évite le « bruit musical »
            g = g > gainPrev[k] ? 0.5f * gainPrev[k] + 0.5f * g : 0.75f * gainPrev[k] + 0.25f * g;
            gainPrev[k] = g;
            re[k] *= g;
            im[k] *= g;
            if (k > 0 && k < N / 2) {
                re[N - k] = re[k];
                im[N - k] = -im[k];
            }
        }
        fft(re, im, true);
        for (int i = 0; i < N; i++) {
            olaBuf[i] += re[i] * window[i];
        }
        for (int i = 0; i < H; i++) {
            out.add(olaBuf[i]);
        }
        System.arraycopy(olaBuf, H, olaBuf, 0, H);
        java.util.Arrays.fill(olaBuf, H, N, 0f);
    }

    /** FFT radix-2 en place. inverse = true : FFT inverse normalisée. */
    static void fft(float[] re, float[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            float wr = (float) Math.cos(ang);
            float wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j;
                    int b = a + len / 2;
                    float ur = re[a], ui = im[a];
                    float vr = re[b] * cr - im[b] * ci;
                    float vi = re[b] * ci + im[b] * cr;
                    re[a] = ur + vr;
                    im[a] = ui + vi;
                    re[b] = ur - vr;
                    im[b] = ui - vi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
