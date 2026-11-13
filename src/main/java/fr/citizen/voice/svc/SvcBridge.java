package fr.citizen.voice.svc;

import de.maxhenkel.voicechat.api.Entity;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import fr.citizen.voice.CitizenVoice;
import fr.citizen.voice.SvcLink;
import fr.citizen.voice.VoiceHub;
import fr.citizen.voice.VoiceLog;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relie le chat vocal web à Simple Voice Chat :
 * - la voix des joueurs PC est décodée (Opus) et envoyée aux navigateurs proches ;
 * - la voix des joueurs navigateur est encodée (Opus) et jouée à la position de leur personnage,
 *   les joueurs PC l'entendent donc en 3D exactement comme un joueur avec le mod.
 */
public final class SvcBridge implements SvcLink {
    private static volatile SvcBridge instance;

    private final VoicechatServerApi api;
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final Map<UUID, Out> outs = new ConcurrentHashMap<>();

    private static final class Out {
        ServerPlayer player;
        EntityAudioChannel channel;
        OpusEncoder encoder;
    }

    private SvcBridge(VoicechatServerApi api) {
        this.api = api;
    }

    static void start(VoicechatServerApi api) {
        SvcBridge b = new SvcBridge(api);
        instance = b;
        VoiceHub hub = CitizenVoice.hub();
        if (hub != null) {
            hub.setSvc(b);
        }
        VoiceLog.info("Relié à Simple Voice Chat (distance " + Math.round(api.getVoiceChatDistance()) + " blocs)");
    }

    static void onMicrophone(MicrophonePacketEvent e) {
        SvcBridge b = instance;
        VoiceHub hub = CitizenVoice.hub();
        if (b == null || hub == null) {
            return;
        }
        VoicechatConnection conn = e.getSenderConnection();
        if (conn == null || conn.getGroup() != null) {
            return; // les groupes Simple Voice Chat restent privés
        }
        UUID sender = conn.getPlayer().getUuid();
        byte[] opus = e.getPacket().getOpusEncodedData();
        OpusDecoder dec = b.decoders.computeIfAbsent(sender, k -> b.api.createDecoder());
        if (dec == null) {
            return;
        }
        if (opus == null || opus.length == 0) {
            synchronized (dec) {
                dec.resetState(); // fin de prise de parole
            }
            return;
        }
        hub.markSpeaking(sender);
        if (!hub.hasWebListeners()) {
            return;
        }
        short[] pcm;
        synchronized (dec) {
            pcm = dec.decode(opus);
        }
        if (pcm != null && pcm.length > 0) {
            hub.onSvcVoice(sender, pcm, e.getPacket().isWhispering());
        }
    }

    @Override
    public void sendWebVoice(UUID uuid, short[] pcm48) {
        ServerPlayer p = CitizenVoice.onlinePlayer(uuid);
        if (p == null) {
            return;
        }
        Out o = outs.computeIfAbsent(uuid, k -> new Out());
        synchronized (o) {
            if (o.channel == null || o.player != p) {
                Entity ent = api.fromEntity(p);
                UUID channelId = UUID.nameUUIDFromBytes(("citizenvoix:" + uuid).getBytes(StandardCharsets.UTF_8));
                o.channel = api.createEntityAudioChannel(channelId, ent);
                o.player = p;
                if (o.channel == null) {
                    return;
                }
            }
            if (o.encoder == null) {
                o.encoder = api.createEncoder();
            }
            o.channel.setDistance((float) CitizenVoice.hub().distance());
            byte[] opus = o.encoder.encode(pcm48);
            if (opus != null && opus.length > 0) {
                o.channel.send(opus);
            }
        }
    }

    @Override
    public void stopWebVoice(UUID uuid) {
        Out o = outs.get(uuid);
        if (o != null) {
            synchronized (o) {
                if (o.encoder != null) {
                    o.encoder.resetState();
                }
                if (CitizenVoice.onlinePlayer(uuid) == null) {
                    if (o.encoder != null) {
                        o.encoder.close();
                    }
                    outs.remove(uuid);
                }
            }
        }
        if (CitizenVoice.onlinePlayer(uuid) == null) {
            OpusDecoder d = decoders.remove(uuid);
            if (d != null) {
                synchronized (d) {
                    d.close();
                }
            }
        }
    }

    @Override
    public double defaultDistance() {
        return api.getVoiceChatDistance();
    }

    @Override
    public boolean hasModInstalled(UUID player) {
        VoicechatConnection c = api.getConnectionOf(player);
        return c != null && c.isInstalled();
    }
}
