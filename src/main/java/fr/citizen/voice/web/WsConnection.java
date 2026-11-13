package fr.citizen.voice.web;

import fr.citizen.voice.VoiceHub;
import fr.citizen.voice.VoiceListener;
import fr.citizen.voice.VoiceLog;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Une connexion WebSocket (RFC 6455) écrite à la main. Lecture sur le thread de la connexion,
 * écriture sur un thread dédié avec une file : un téléphone lent ne bloque jamais le serveur.
 */
public final class WsConnection implements VoiceListener {
    private static final byte[] CLOSE = {};
    private static final int MAX_AUDIO_QUEUED = 25; // 0,5 s : au-delà on jette (mieux vaut une coupure qu'un retard)

    public volatile UUID uuid;
    public volatile String name = "?";
    public volatile boolean muted;
    public volatile boolean deaf;
    public volatile boolean whisper;
    public final String remoteIp;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final VoiceHub hub;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger audioQueued = new AtomicInteger();
    private volatile boolean open = true;

    WsConnection(Socket socket, InputStream in, OutputStream out, VoiceHub hub, String remoteIp) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.hub = hub;
        this.remoteIp = remoteIp;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public UUID listenerUuid() {
        return uuid;
    }

    @Override
    public boolean isDeaf() {
        return deaf;
    }

    @Override
    public void sendVoice(UUID speaker, float gain, float pan, short[] pcm16, Encoded enc) {
        sendAudio(VoiceHub.webFrame(speaker, gain, pan, enc.le16()));
    }

    public void sendText(String s) {
        if (open) {
            queue.add(frame(0x1, s.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public void sendAudio(byte[] b) {
        if (!open) {
            return;
        }
        if (audioQueued.get() >= MAX_AUDIO_QUEUED) {
            return;
        }
        audioQueued.incrementAndGet();
        queue.add(frame(0x2, b)); // seules les trames audio sont binaires : décomptées dans writeLoop
    }

    public void close() {
        if (open) {
            open = false;
            queue.add(CLOSE);
        }
    }

    void run() {
        Thread writer = Thread.ofVirtual().name("citizenvoix-ws-write").start(this::writeLoop);
        try {
            socket.setSoTimeout(45_000);
            ByteArrayOutputStream partial = null;
            int partialOp = 0;
            while (open) {
                int b0 = in.read();
                if (b0 < 0) {
                    break;
                }
                int b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | readByte();
                    }
                }
                if (len > 65536 || !masked) {
                    break; // trame anormale
                }
                byte[] mask = readN(4);
                byte[] payload = readN((int) len);
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
                if (op == 0x8) {
                    break;
                } else if (op == 0x9) {
                    queue.add(frame(0xA, payload));
                    continue;
                } else if (op == 0xA) {
                    continue;
                }
                if (op != 0) {
                    partialOp = op;
                    partial = null;
                }
                if (!fin || partial != null) {
                    if (partial == null) {
                        partial = new ByteArrayOutputStream();
                    }
                    partial.writeBytes(payload);
                    if (partial.size() > 65536) {
                        break;
                    }
                    if (!fin) {
                        continue;
                    }
                    payload = partial.toByteArray();
                    partial = null;
                }
                if (partialOp == 0x1) {
                    hub.onText(this, new String(payload, StandardCharsets.UTF_8));
                } else if (partialOp == 0x2) {
                    hub.onBinary(this, payload);
                }
            }
        } catch (SocketTimeoutException | EOFException ignored) {
        } catch (IOException e) {
            if (open) {
                VoiceLog.info("Connexion web fermée : " + e.getMessage());
            }
        } catch (Throwable t) {
            VoiceLog.error("Erreur WebSocket", t);
        } finally {
            close();
            hub.onClose(this);
            try {
                writer.join(2000);
            } catch (InterruptedException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] f = queue.poll(30, TimeUnit.SECONDS);
                if (f == null) {
                    if (!open) {
                        return;
                    }
                    continue;
                }
                if (f == CLOSE) {
                    out.write(frame(0x8, new byte[]{0x03, (byte) 0xE8}));
                    out.flush();
                    socket.close();
                    return;
                }
                if ((f[0] & 0x0F) == 0x2) {
                    audioQueued.decrementAndGet();
                }
                out.write(f);
                if (queue.isEmpty()) {
                    out.flush();
                }
            }
        } catch (Exception e) {
            open = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private byte[] readN(int n) throws IOException {
        byte[] b = in.readNBytes(n);
        if (b.length != n) {
            throw new EOFException();
        }
        return b;
    }

    static byte[] frame(int op, byte[] payload) {
        int n = payload.length;
        int head = n < 126 ? 2 : (n < 65536 ? 4 : 10);
        byte[] f = new byte[head + n];
        f[0] = (byte) (0x80 | op);
        if (n < 126) {
            f[1] = (byte) n;
        } else if (n < 65536) {
            f[1] = 126;
            f[2] = (byte) (n >> 8);
            f[3] = (byte) n;
        } else {
            f[1] = 127;
            for (int i = 0; i < 8; i++) {
                f[2 + i] = (byte) ((long) n >> (56 - 8 * i));
            }
        }
        System.arraycopy(payload, 0, f, head, n);
        return f;
    }
}
