package fr.citizen.voice.client;

import fr.citizen.voice.Audio;
import fr.citizen.voice.Denoiser;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Moteur audio du joueur PC, sans dépendance (Java Sound) :
 * - capture du micro choisi -> trames 16 kHz de 20 ms -> envoyées au serveur ;
 * - lecture des voix reçues sur la sortie choisie, mixées en stéréo 48 kHz.
 * Utilisable sans Minecraft (tests).
 */
public final class AudioEngine {
    public static final UUID SELF_TEST = new UUID(0, 0);
    private static final int OUT_RATE = 48000;
    private static final int OUT_FRAME = 960; // 20 ms

    public interface Settings {
        String micro();

        String sortie();

        boolean appuyerPourParler();

        int sensibilite();

        int gainMicro();

        int volume();

        boolean microCoupe();

        boolean sourdine();

        boolean reducteurBruit();
    }

    private final Settings cfg;
    /** Reçoit chaque trame à envoyer : (PCM 16 kHz 320 échantillons). */
    private final Consumer<short[]> sender;
    /** Vrai si le joueur est dans un monde où le serveur a le chat vocal. */
    private final BooleanSupplier canSend;

    public volatile boolean pttDown;
    public volatile boolean whisperDown;
    public volatile boolean testMic;
    /** Niveau du micro 0..1 (pour le vu-mètre). */
    public volatile float level;
    public volatile boolean talking;
    public volatile String micError = null;
    public volatile String outError = null;

    private final Map<UUID, Spk> speakers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> heard = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread capThread;
    private Thread playThread;
    private volatile TargetDataLine micLine;
    private volatile SourceDataLine outLine;

    private static final class Spk {
        final ConcurrentLinkedQueue<short[]> q = new ConcurrentLinkedQueue<>();
        volatile float gain;
        volatile float pan;
        float g;
        float p;
        short[] cur;
        double idx;
        boolean started;
        volatile long last;
    }

    public AudioEngine(Settings cfg, Consumer<short[]> sender, BooleanSupplier canSend) {
        this.cfg = cfg;
        this.sender = sender;
        this.canSend = canSend;
    }

    // ------------------------------------------------------------------ périphériques

    public static List<String> inputs() {
        return devices(TargetDataLine.class);
    }

    public static List<String> outputs() {
        return devices(SourceDataLine.class);
    }

    private static List<String> devices(Class<? extends Line> kind) {
        List<String> l = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(info);
                if (m.isLineSupported(new Line.Info(kind)) && !l.contains(info.getName())) {
                    l.add(info.getName());
                }
            } catch (Throwable ignored) {
            }
        }
        return l;
    }

    private static Line openLine(Class<? extends DataLine> kind, String device, AudioFormat fmt) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(kind, fmt);
        if (device != null && !device.isEmpty()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(device)) {
                    Mixer m = AudioSystem.getMixer(mi);
                    if (m.isLineSupported(info)) {
                        return m.getLine(info);
                    }
                }
            }
        }
        return AudioSystem.getLine(info);
    }

    // ------------------------------------------------------------------ cycle de vie

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        capThread = new Thread(this::captureLoop, "CitizenVoix-micro");
        capThread.setDaemon(true);
        capThread.start();
        playThread = new Thread(this::playLoop, "CitizenVoix-sortie");
        playThread.setDaemon(true);
        playThread.start();
    }

    public synchronized void stop() {
        running = false;
        TargetDataLine m = micLine;
        if (m != null) {
            m.close();
        }
        SourceDataLine o = outLine;
        if (o != null) {
            o.close();
        }
        for (Thread t : new Thread[]{capThread, playThread}) {
            if (t != null) {
                try {
                    t.join(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        speakers.clear();
        level = 0;
        talking = false;
    }

    public void restart() {
        stop();
        start();
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ voix reçues

    /** Appelé par le réseau : 320 échantillons 16 kHz. */
    public void enqueue(UUID speaker, float gain, float pan, short[] pcm16) {
        if (cfg.sourdine() && !SELF_TEST.equals(speaker)) {
            return;
        }
        Spk s = speakers.computeIfAbsent(speaker, k -> {
            Spk n = new Spk();
            n.g = gain;
            n.p = pan;
            return n;
        });
        s.gain = gain;
        s.pan = pan;
        s.q.add(pcm16);
        while (s.q.size() > 15) {
            s.q.poll(); // retard max ~300 ms
        }
        long now = System.currentTimeMillis();
        s.last = now;
        if (!SELF_TEST.equals(speaker)) {
            heard.put(speaker, now);
        }
    }

    /** Joueurs entendus dans les 400 dernières ms (pour l'affichage). */
    public List<UUID> speakingNow() {
        long now = System.currentTimeMillis();
        List<UUID> l = new ArrayList<>();
        heard.forEach((u, t) -> {
            if (now - t < 400) {
                l.add(u);
            }
        });
        heard.values().removeIf(t -> now - t > 5000);
        return l;
    }

    // ------------------------------------------------------------------ capture

    private void captureLoop() {
        int[] rates = {16000, 48000, 44100};
        int vadHold = 0;
        while (running) {
            TargetDataLine line = null;
            int rate = 0;
            for (int r : rates) {
                try {
                    AudioFormat f = new AudioFormat(r, 16, 1, true, false);
                    line = (TargetDataLine) openLine(TargetDataLine.class, cfg.micro(), f);
                    line.open(f, r / 50 * 2 * 4);
                    rate = r;
                    break;
                } catch (Throwable t) {
                    line = null;
                }
            }
            if (line == null) {
                micError = "Micro introuvable ou occupé";
                sleep(3000);
                continue;
            }
            micError = null;
            micLine = line;
            line.start();
            int chunk = rate / 50; // 20 ms
            byte[] buf = new byte[chunk * 2];
            double step = rate / 16000.0;
            double pos = 1;
            float prev = 0;
            short[] out = new short[Audio.WEB_FRAME];
            int n = 0;
            try {
                while (running) {
                    int read = 0;
                    while (read < buf.length && running) {
                        int r = line.read(buf, read, buf.length - read);
                        if (r <= 0) {
                            break;
                        }
                        read += r;
                    }
                    if (read < buf.length) {
                        break;
                    }
                    float gain = cfg.gainMicro() / 100f;
                    for (int i = 0; i < chunk; i++) {
                        float s = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8)) * gain;
                        while (pos <= 1) {
                            float v = prev + (s - prev) * (float) pos;
                            out[n++] = (short) Math.max(-32768, Math.min(32767, v));
                            if (n == Audio.WEB_FRAME) {
                                vadHold = onFrame(out, vadHold);
                                out = new short[Audio.WEB_FRAME];
                                n = 0;
                            }
                            pos += step;
                        }
                        pos -= 1;
                        prev = s;
                    }
                }
            } catch (Throwable t) {
                micError = "Micro déconnecté";
            } finally {
                line.close();
                micLine = null;
            }
        }
    }

    private Denoiser denoiser = new Denoiser();
    private boolean denoiserWasOn;

    private int onFrame(short[] pcm, int vadHold) {
        boolean dn = cfg.reducteurBruit();
        if (dn) {
            if (!denoiserWasOn) {
                denoiser = new Denoiser(); // repart de zéro pour réapprendre le bruit de fond
            }
            pcm = denoiser.process(pcm);
        }
        denoiserWasOn = dn;
        double acc = 0;
        for (short v : pcm) {
            acc += (double) v * v;
        }
        double rms = Math.sqrt(acc / pcm.length) / 32768.0;
        float lvl = (float) Math.max(0, Math.min(1, (20 * Math.log10(rms + 1e-6) + 60) / 60));
        level = lvl;
        boolean active;
        if (cfg.appuyerPourParler()) {
            active = pttDown || whisperDown; // en mode touche, chuchoter fait aussi parler
        } else {
            float threshold = 1 - cfg.sensibilite() / 100f;
            if (lvl > threshold * 0.8f) {
                vadHold = 15; // garde le micro ouvert 300 ms après la dernière parole
            }
            active = vadHold-- > 0;
        }
        if (testMic) {
            enqueue(SELF_TEST, 1f, 0f, pcm);
            talking = active;
            return vadHold;
        }
        active = active && !cfg.microCoupe() && canSend.getAsBoolean();
        talking = active;
        if (active) {
            sender.accept(pcm);
        }
        return vadHold;
    }

    // ------------------------------------------------------------------ lecture

    private void playLoop() {
        while (running) {
            AudioFormat f = new AudioFormat(OUT_RATE, 16, 2, true, false);
            SourceDataLine line;
            try {
                line = (SourceDataLine) openLine(SourceDataLine.class, cfg.sortie(), f);
                line.open(f, OUT_FRAME * 4 * 4); // 80 ms de tampon
            } catch (Throwable t) {
                outError = "Sortie son introuvable";
                sleep(3000);
                continue;
            }
            outError = null;
            outLine = line;
            line.start();
            byte[] bytes = new byte[OUT_FRAME * 4];
            float[] l = new float[OUT_FRAME];
            float[] r = new float[OUT_FRAME];
            try {
                while (running) {
                    mix(l, r);
                    for (int i = 0; i < OUT_FRAME; i++) {
                        int a = (int) (Math.max(-1, Math.min(1, l[i])) * 32767);
                        int b = (int) (Math.max(-1, Math.min(1, r[i])) * 32767);
                        bytes[4 * i] = (byte) a;
                        bytes[4 * i + 1] = (byte) (a >> 8);
                        bytes[4 * i + 2] = (byte) b;
                        bytes[4 * i + 3] = (byte) (b >> 8);
                    }
                    line.write(bytes, 0, bytes.length); // bloque ~20 ms : donne le rythme
                }
            } catch (Throwable t) {
                outError = "Sortie son déconnectée";
            } finally {
                line.close();
                outLine = null;
            }
        }
    }

    /** Mixe 20 ms de toutes les voix (16 kHz -> 48 kHz, gain + panoramique). Visible pour les tests. */
    void mix(float[] l, float[] r) {
        java.util.Arrays.fill(l, 0);
        java.util.Arrays.fill(r, 0);
        float vol = cfg.volume() / 100f;
        double step = 16000.0 / OUT_RATE;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Spk> e : speakers.entrySet()) {
            Spk sp = e.getValue();
            if (!sp.started) {
                if (sp.q.size() >= 3) {
                    sp.started = true; // 60 ms de tampon anti-saccades
                } else {
                    if (now - sp.last > 5000) {
                        speakers.remove(e.getKey());
                    }
                    continue;
                }
            }
            for (int i = 0; i < l.length; i++) {
                if (sp.cur == null || sp.idx >= sp.cur.length) {
                    if (sp.cur != null) {
                        sp.idx -= sp.cur.length;
                    }
                    sp.cur = sp.q.poll();
                    if (sp.cur == null) {
                        sp.started = false;
                        sp.idx = 0;
                        break;
                    }
                }
                sp.g += (sp.gain - sp.g) * 0.002f;
                sp.p += (sp.pan - sp.p) * 0.002f;
                int i0 = (int) sp.idx;
                float f = (float) (sp.idx - i0);
                float s0 = sp.cur[i0];
                short[] nx = sp.q.peek();
                float s1 = i0 + 1 < sp.cur.length ? sp.cur[i0 + 1] : (nx != null ? nx[0] : s0);
                float v = (s0 + (s1 - s0) * f) / 32768f * sp.g * vol;
                double a = (sp.p + 1) * Math.PI / 4;
                l[i] += (float) (v * Math.cos(a) * 1.414);
                r[i] += (float) (v * Math.sin(a) * 1.414);
                sp.idx += step;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
