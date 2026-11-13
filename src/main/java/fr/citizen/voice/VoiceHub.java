package fr.citizen.voice;

import fr.citizen.voice.web.WsConnection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cœur du chat vocal : codes d'accès, sessions navigateur et routage de la voix entre
 * joueurs PC (mod) et joueurs console/téléphone (page web), selon la distance.
 */
public final class VoiceHub {
    private record Pending(UUID uuid, String name, long expires) {
    }

    private record Token(UUID uuid, String name, long expires) {
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final long SPEAK_MS = 350;

    private final VoiceConfig config;

    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    /** Navigateurs (un seul par joueur). */
    private final Map<UUID, WsConnection> clients = new ConcurrentHashMap<>();
    /** Joueurs PC avec le mod. */
    private final Map<UUID, VoiceListener> pcClients = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSpoke = new ConcurrentHashMap<>();
    private final Map<String, int[]> failsByIp = new ConcurrentHashMap<>();
    private volatile long failsWindowStart = System.currentTimeMillis();

    public VoiceHub(VoiceConfig config) {
        this.config = config;
    }

    public VoiceConfig config() {
        return config;
    }

    public double distance() {
        return config.distance;
    }

    // ------------------------------------------------------------------ joueurs PC

    public void registerPc(VoiceListener l) {
        pcClients.put(l.listenerUuid(), l);
    }

    public void unregisterPc(UUID uuid) {
        pcClients.remove(uuid);
    }

    public boolean hasPcMod(UUID uuid) {
        return pcClients.containsKey(uuid);
    }

    public int pcCount() {
        return pcClients.size();
    }

    /** Trame micro d'un joueur PC (PCM 16 kHz, 320 échantillons). */
    public void onPcMic(UUID sender, short[] pcm16, boolean whisper) {
        if (pcm16.length != Audio.WEB_FRAME) {
            return;
        }
        Players.Snap from = Players.get(sender);
        if (from == null || from.spectator()) {
            return;
        }
        lastSpoke.put(sender, System.currentTimeMillis());
        route(sender, from, pcm16, whisper);
    }

    // ------------------------------------------------------------------ codes / sessions web

    public String createCode(UUID uuid, String name) {
        codes.values().removeIf(p -> p.uuid.equals(uuid) || p.expires < System.currentTimeMillis());
        String code;
        do {
            code = String.format("%06d", RNG.nextInt(1_000_000));
        } while (codes.containsKey(code));
        codes.put(code, new Pending(uuid, name, System.currentTimeMillis() + config.dureeCodeMinutes * 60_000L));
        return code;
    }

    /** Code encore valable d'un joueur, ou null. */
    public String currentCode(UUID uuid) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Pending> e : codes.entrySet()) {
            if (e.getValue().uuid.equals(uuid) && e.getValue().expires > now) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Tous les codes encore valables : « pseudo -> code (minutes restantes) ». */
    public List<String[]> activeCodes() {
        long now = System.currentTimeMillis();
        List<String[]> l = new ArrayList<>();
        codes.forEach((code, p) -> {
            if (p.expires > now) {
                l.add(new String[]{p.name, code, String.valueOf(Math.max(1, (p.expires - now + 59_999) / 60_000))});
            }
        });
        l.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        return l;
    }

    public boolean isConnected(UUID uuid) {
        WsConnection c = clients.get(uuid);
        return c != null && c.isOpen();
    }

    public List<String> connectedNames() {
        List<String> l = new ArrayList<>();
        for (WsConnection c : clients.values()) {
            if (c.isOpen()) {
                l.add(c.name + (c.muted ? " (micro coupé)" : "") + (c.deaf ? " (sourdine)" : ""));
            }
        }
        return l;
    }

    public void disconnect(UUID uuid, String reason) {
        WsConnection c = clients.remove(uuid);
        if (c != null) {
            c.sendText("{\"t\":\"err\",\"msg\":" + json(reason) + ",\"fatal\":true}");
            c.close();
        }
    }

    public void disconnectAll() {
        for (UUID u : new ArrayList<>(clients.keySet())) {
            disconnect(u, "Serveur vocal arrêté.");
        }
    }

    // ------------------------------------------------------------------ messages du navigateur

    private static final Pattern STR = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern BOOL = Pattern.compile("\"(\\w+)\"\\s*:\\s*(true|false)");

    private static String field(String json, String key) {
        Matcher m = STR.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(key)) {
                return m.group(2);
            }
        }
        return null;
    }

    private static Boolean bool(String json, String key) {
        Matcher m = BOOL.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(key)) {
                return Boolean.parseBoolean(m.group(2));
            }
        }
        return null;
    }

    public void onText(WsConnection c, String msg) {
        String t = field(msg, "t");
        if (t == null) {
            return;
        }
        Boolean v = bool(msg, "v");
        switch (t) {
            case "auth" -> auth(c, field(msg, "code"), field(msg, "token"));
            case "mute" -> {
                if (v != null) {
                    c.muted = v;
                }
            }
            case "deaf" -> {
                if (v != null) {
                    c.deaf = v;
                }
            }
            case "whisper" -> {
                if (v != null) {
                    c.whisper = v;
                }
            }
            case "ping" -> c.sendText("{\"t\":\"pong\"}");
            default -> {
            }
        }
    }

    private boolean tooManyFails(String ip) {
        long now = System.currentTimeMillis();
        if (now - failsWindowStart > 10 * 60_000L) {
            failsByIp.clear();
            failsWindowStart = now;
        }
        int[] f = failsByIp.get(ip);
        return f != null && f[0] >= 10;
    }

    private void fail(WsConnection c, String why) {
        failsByIp.computeIfAbsent(c.remoteIp, k -> new int[1])[0]++;
        c.sendText("{\"t\":\"err\",\"msg\":" + json(why) + "}");
    }

    private void auth(WsConnection c, String code, String token) {
        if (c.uuid != null) {
            return;
        }
        if (tooManyFails(c.remoteIp)) {
            c.sendText("{\"t\":\"err\",\"msg\":\"Trop d'essais. Réessaie dans 10 minutes.\",\"fatal\":true}");
            c.close();
            return;
        }
        long now = System.currentTimeMillis();
        UUID uuid;
        String name;
        if (token != null && !token.isEmpty()) {
            Token tk = tokens.get(token);
            if (tk == null || tk.expires < now) {
                tokens.remove(token);
                c.sendText("{\"t\":\"err\",\"msg\":\"Session expirée : tape /voix en jeu pour un nouveau code.\",\"expired\":true}");
                return;
            }
            uuid = tk.uuid;
            name = tk.name;
        } else if (code != null) {
            Pending p = codes.remove(code.trim());
            if (p == null || p.expires < now) {
                fail(c, "Code invalide ou expiré. Tape /voix en jeu pour en recevoir un.");
                return;
            }
            uuid = p.uuid;
            name = p.name;
            tokens.values().removeIf(x -> x.expires < now);
            byte[] b = new byte[24];
            RNG.nextBytes(b);
            token = HexFormat.of().formatHex(b);
            tokens.put(token, new Token(uuid, name, now + config.dureeSessionHeures * 3_600_000L));
        } else {
            return;
        }
        c.uuid = uuid;
        c.name = name;
        WsConnection old = clients.put(uuid, c);
        if (old != null && old != c) {
            old.sendText("{\"t\":\"err\",\"msg\":\"Connecté sur un autre appareil.\",\"fatal\":true}");
            old.close();
        }
        c.sendText("{\"t\":\"ok\",\"name\":" + json(name) + ",\"token\":" + json(token)
                + ",\"distance\":" + Math.round(distance()) + ",\"whisper\":" + Math.round(config.distanceChuchotement) + "}");
        VoiceLog.info(name + " a rejoint le chat vocal web (" + c.remoteIp + ")");
    }

    public void onClose(WsConnection c) {
        if (c.uuid != null && clients.remove(c.uuid, c)) {
            VoiceLog.info(c.name + " a quitté le chat vocal web");
        }
    }

    /** Trame micro du navigateur : PCM 16 bits LE, 16 kHz, 320 échantillons. */
    public void onBinary(WsConnection c, byte[] data) {
        if (c.uuid == null || c.muted || data.length != Audio.WEB_FRAME * 2) {
            return;
        }
        Players.Snap me = Players.get(c.uuid);
        if (me == null || me.spectator()) {
            return; // pas en jeu : personne ne peut l'entendre
        }
        lastSpoke.put(c.uuid, System.currentTimeMillis());
        route(c.uuid, me, Audio.bytesToShorts(data), c.whisper);
    }

    // ------------------------------------------------------------------ routage

    private void route(UUID sender, Players.Snap from, short[] pcm, boolean whisper) {
        double max = whisper ? config.distanceChuchotement : distance();
        VoiceListener.Encoded enc = new VoiceListener.Encoded(pcm);
        for (WsConnection l : clients.values()) {
            deliver(l, sender, from, pcm, enc, max);
        }
        for (VoiceListener l : pcClients.values()) {
            deliver(l, sender, from, pcm, enc, max);
        }
    }

    private static void deliver(VoiceListener l, UUID sender, Players.Snap from, short[] pcm, VoiceListener.Encoded enc, double max) {
        UUID lu = l.listenerUuid();
        if (lu == null || lu.equals(sender) || l.isDeaf() || !l.isOpen()) {
            return;
        }
        Players.Snap me = Players.get(lu);
        if (me == null || !me.level().equals(from.level())) {
            return;
        }
        float g = Audio.gain(me.distTo(from), max);
        if (g <= 0f) {
            return;
        }
        float pan = Audio.pan(me.x(), me.z(), me.yaw(), from.x(), from.z());
        try {
            l.sendVoice(sender, g, pan, pcm, enc);
        } catch (Throwable t) {
            VoiceLog.error("Envoi de la voix impossible", t);
        }
    }

    /** Trame binaire pour le navigateur : [1,0,0,0][id int32][gain f32][pan f32][PCM16 LE]. */
    public static byte[] webFrame(UUID speaker, float gain, float pan, byte[] le16) {
        ByteBuffer bb = ByteBuffer.allocate(16 + le16.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 1).put((byte) 0).put((byte) 0).put((byte) 0);
        bb.putInt(speaker.hashCode()).putFloat(gain).putFloat(pan).put(le16);
        return bb.array();
    }

    // ------------------------------------------------------------------ état affiché sur la page

    /** Appelé 4 fois par seconde : liste des joueurs à portée, qui parle, etc. */
    public void pushState() {
        long now = System.currentTimeMillis();
        double max = distance();
        for (WsConnection c : clients.values()) {
            if (c.uuid == null || !c.isOpen()) {
                continue;
            }
            Players.Snap me = Players.get(c.uuid);
            StringBuilder sb = new StringBuilder("{\"t\":\"state\",\"inGame\":").append(me != null)
                    .append(",\"speaking\":").append(isSpeaking(c.uuid, now)).append(",\"near\":[");
            if (me != null) {
                List<Players.Snap> near = new ArrayList<>();
                for (Players.Snap o : Players.all()) {
                    if (!o.uuid().equals(c.uuid) && o.level().equals(me.level()) && o.distTo(me) <= max) {
                        near.add(o);
                    }
                }
                near.sort((a, b) -> Double.compare(a.distTo(me), b.distTo(me)));
                boolean first = true;
                for (Players.Snap o : near) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    boolean web = isConnected(o.uuid());
                    sb.append("{\"n\":").append(json(o.name()))
                            .append(",\"d\":").append(Math.round(o.distTo(me)))
                            .append(",\"s\":").append(isSpeaking(o.uuid(), now))
                            .append(",\"v\":").append(web || hasPcMod(o.uuid()))
                            .append(",\"w\":").append(web).append('}');
                }
            }
            sb.append("]}");
            c.sendText(sb.toString());
        }
    }

    public boolean isSpeaking(UUID u, long now) {
        Long t = lastSpoke.get(u);
        return t != null && now - t < SPEAK_MS;
    }

    // ------------------------------------------------------------------ utilitaires

    public static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
