package fr.citizen.voice.web;

import fr.citizen.voice.VoiceConfig;
import fr.citizen.voice.VoiceHub;
import fr.citizen.voice.VoiceLog;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mini serveur HTTPS + WebSocket sans dépendance. Sert la page du chat vocal et le canal audio.
 * Une requête http:// tapée par erreur sur le port est automatiquement redirigée vers https://.
 */
public final class WebServer {
    private final VoiceHub hub;
    private final VoiceConfig cfg;
    private ServerSocket server;
    private SSLSocketFactory tls;
    private volatile boolean running;

    public WebServer(VoiceHub hub) {
        this.hub = hub;
        this.cfg = hub.config();
    }

    public synchronized void start() throws Exception {
        if (cfg.https) {
            KeyStore ks;
            char[] pass;
            if (!cfg.certificat.isEmpty()) {
                Path p = cfg.dir().resolve(cfg.certificat);
                ks = KeyStore.getInstance("PKCS12");
                pass = cfg.certificatMotDePasse.toCharArray();
                try (InputStream in = Files.newInputStream(p)) {
                    ks.load(in, pass);
                }
                VoiceLog.info("Certificat chargé : " + p);
            } else {
                ks = SelfSignedCert.loadOrCreate(cfg.dir().resolve("auto-signe.p12"), cfg.adressePublique);
                pass = SelfSignedCert.PASSWORD;
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            tls = ctx.getSocketFactory();
        }
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(cfg.port));
        running = true;
        Thread.ofPlatform().daemon().name("citizenvoix-web").start(this::acceptLoop);
        VoiceLog.info("Chat vocal web en ligne : " + cfg.publicUrl());
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread.ofVirtual().name("citizenvoix-http").start(() -> handleRaw(s));
            } catch (IOException e) {
                if (running) {
                    VoiceLog.error("Accept", e);
                }
            }
        }
    }

    private void handleRaw(Socket raw) {
        try {
            raw.setSoTimeout(15_000);
            raw.setTcpNoDelay(true);
            InputStream rin = raw.getInputStream();
            int first = rin.read();
            if (first < 0) {
                raw.close();
                return;
            }
            String ip = raw.getInetAddress().getHostAddress();
            if (tls != null) {
                if (first == 0x16) {
                    SSLSocket s = (SSLSocket) tls.createSocket(raw, new ByteArrayInputStream(new byte[]{(byte) first}), true);
                    s.setUseClientMode(false);
                    s.startHandshake();
                    handle(s, s.getInputStream(), s.getOutputStream(), ip);
                } else {
                    // http:// sur le port https -> redirection
                    InputStream in = new SequenceInputStream(new ByteArrayInputStream(new byte[]{(byte) first}), rin);
                    Request r = Request.read(in);
                    String path = r == null ? "/" : r.path;
                    String body = "<a href=\"" + cfg.publicUrl() + path + "\">" + cfg.publicUrl() + "</a>";
                    respond(raw.getOutputStream(), 301, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8),
                            "Location: " + cfg.publicUrl() + path + "\r\n");
                    raw.close();
                }
            } else {
                InputStream in = new SequenceInputStream(new ByteArrayInputStream(new byte[]{(byte) first}), rin);
                handle(raw, in, raw.getOutputStream(), ip);
            }
        } catch (Exception e) {
            try {
                raw.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handle(Socket s, InputStream in, OutputStream out, String ip) throws Exception {
        Request r = Request.read(in);
        if (r == null) {
            s.close();
            return;
        }
        String path = r.path;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (path.equals("/ws") && "websocket".equalsIgnoreCase(r.headers.getOrDefault("upgrade", ""))) {
            String key = r.headers.get("sec-websocket-key");
            if (key == null) {
                respond(out, 400, "text/plain", "Bad request".getBytes(), "");
                s.close();
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            new WsConnection(s, in, out, hub, ip).run();
            return;
        }
        String file = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/app.js" -> "app.js";
            case "/worklet.js" -> "worklet.js";
            case "/icon.svg" -> "icon.svg";
            case "/manifest.json" -> "manifest.json";
            default -> null;
        };
        if (file == null) {
            respond(out, 404, "text/plain; charset=utf-8", "Introuvable".getBytes(StandardCharsets.UTF_8), "");
        } else {
            byte[] data;
            try (InputStream res = WebServer.class.getResourceAsStream("/web/" + file)) {
                data = res == null ? new byte[0] : res.readAllBytes();
            }
            String type = file.endsWith(".html") ? "text/html; charset=utf-8"
                    : file.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : file.endsWith(".svg") ? "image/svg+xml"
                    : "application/manifest+json";
            respond(out, 200, type, data, "Cache-Control: no-cache\r\n");
        }
        s.close();
    }

    private static void respond(OutputStream out, int code, String type, byte[] body, String extra) throws IOException {
        String status = switch (code) {
            case 200 -> "OK";
            case 301 -> "Moved Permanently";
            case 400 -> "Bad Request";
            default -> "Not Found";
        };
        String h = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
                + "Permissions-Policy: microphone=(self)\r\n" + extra + "\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    static final class Request {
        String method;
        String path;
        final Map<String, String> headers = new HashMap<>();

        static Request read(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0) {
                    return null;
                }
                buf.write(b);
                if (buf.size() > 16384) {
                    return null;
                }
                state = switch (state) {
                    case 0, 2 -> b == '\r' ? state + 1 : 0;
                    case 1 -> b == '\n' ? 2 : 0;
                    case 3 -> b == '\n' ? 4 : 0;
                    default -> 0;
                };
            }
            String[] lines = buf.toString(StandardCharsets.ISO_8859_1).split("\r\n");
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) {
                return null;
            }
            Request r = new Request();
            r.method = rl[0];
            r.path = rl[1];
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
                }
            }
            return r;
        }
    }
}
