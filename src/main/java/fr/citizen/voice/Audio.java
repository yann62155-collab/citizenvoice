package fr.citizen.voice;

/**
 * Outils audio. Le navigateur échange du PCM 16 bits mono 16 kHz (320 échantillons = 20 ms) ;
 * Simple Voice Chat travaille en Opus 48 kHz (960 échantillons = 20 ms).
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
}
