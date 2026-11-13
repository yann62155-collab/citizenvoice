package fr.citizen.voice;

/**
 * Outils audio. Le navigateur échange du PCM 16 bits mono 16 kHz (320 échantillons = 20 ms) ;
 * Le mod PC échange la même chose compressée en G.711 µ-law (8 bits par échantillon, 16 Ko/s).
 */
public final class Audio {
    public static final int WEB_RATE = 16000;
    public static final int WEB_FRAME = 320;
    public static final int SVC_FRAME = 960;

    private Audio() {
    }

    /** 48 kHz -> 16 kHz (moyenne de 3 échantillons, filtre passe-bas simple). */
    public static short[] down48to16(short[] in) {
        int n = in.length / 3;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int j = i * 3;
            out[i] = (short) ((in[j] + in[j + 1] + in[j + 2]) / 3);
        }
        return out;
    }

    /** 16 kHz -> 48 kHz (interpolation linéaire). */
    public static short[] up16to48(short[] in) {
        short[] out = new short[in.length * 3];
        for (int i = 0; i < in.length; i++) {
            int a = in[i];
            int b = i + 1 < in.length ? in[i + 1] : a;
            out[i * 3] = (short) a;
            out[i * 3 + 1] = (short) (a + (b - a) / 3);
            out[i * 3 + 2] = (short) (a + 2 * (b - a) / 3);
        }
        return out;
    }

    public static short[] bytesToShorts(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
        }
        return s;
    }

    /** Atténuation linéaire avec la distance, comme Simple Voice Chat. */
    public static float gain(double dist, double max) {
        if (dist >= max) {
            return 0f;
        }
        return (float) Math.max(0, 1.0 - dist / max);
    }

    /**
     * Panoramique stéréo (-1 = gauche, +1 = droite) d'une source vue depuis l'auditeur.
     * yaw = rotation Minecraft de l'auditeur en degrés.
     */
    public static float pan(double lx, double lz, float yawDeg, double sx, double sz) {
        double dx = sx - lx;
        double dz = sz - lz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) {
            return 0f;
        }
        double yaw = Math.toRadians(yawDeg);
        // Direction du regard dans Minecraft : (-sin(yaw), cos(yaw)) ; droite = (-cos(yaw), -sin(yaw))
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        return (float) Math.max(-1, Math.min(1, (dx * rx + dz * rz) / len));
    }

    // ---- G.711 µ-law : compression 2:1 sans dépendance, qualité « téléphone HD » à 16 kHz
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;
    private static final short[] ULAW_TABLE = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            int u = ~i & 0xFF;
            int t = ((u & 0x0F) << 3) + BIAS;
            t <<= (u & 0x70) >> 4;
            ULAW_TABLE[i] = (short) ((u & 0x80) != 0 ? BIAS - t : t - BIAS);
        }
    }

    public static byte[] toUlaw(short[] pcm) {
        byte[] out = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            int s = pcm[i];
            int sign = (s >> 8) & 0x80;
            if (sign != 0) {
                s = -s;
            }
            if (s > CLIP) {
                s = CLIP;
            }
            s += BIAS;
            int exp = 7;
            for (int mask = 0x4000; (s & mask) == 0 && exp > 0; mask >>= 1) {
                exp--;
            }
            int mant = (s >> (exp + 3)) & 0x0F;
            out[i] = (byte) ~(sign | (exp << 4) | mant);
        }
        return out;
    }

    public static short[] fromUlaw(byte[] u) {
        short[] out = new short[u.length];
        for (int i = 0; i < u.length; i++) {
            out[i] = ULAW_TABLE[u[i] & 0xFF];
        }
        return out;
    }

    public static byte[] shortsToBytes(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[2 * i] = (byte) s[i];
            b[2 * i + 1] = (byte) (s[i] >> 8);
        }
        return b;
    }
}
