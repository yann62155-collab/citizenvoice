package fr.citizen.voice.net;

import fr.citizen.voice.Audio;
import fr.citizen.voice.CitizenVoice;
import fr.citizen.voice.VoiceHub;
import fr.citizen.voice.VoiceListener;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Enregistrement des paquets. Les paquets sont « optionnels » : un joueur sans le mod
 * (Xbox, téléphone via Citizen Bedrock) peut toujours se connecter.
 * La voix est traitée directement sur le thread réseau, jamais sur le thread du jeu.
 */
public final class VoiceNetwork {
    public static final int PROTOCOL = 1;

    /** Remplis par la partie client (jamais chargée sur le serveur dédié). */
    public static volatile Consumer<Payloads.Voice> clientVoice = p -> {
    };
    public static volatile Consumer<Payloads.Welcome> clientWelcome = p -> {
    };

    private VoiceNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar("1").optional().executesOn(HandlerThread.NETWORK);
        r.playToServer(Payloads.Hello.TYPE, Payloads.Hello.CODEC, VoiceNetwork::onHello);
        r.playToServer(Payloads.Mic.TYPE, Payloads.Mic.CODEC, VoiceNetwork::onMic);
        r.playToClient(Payloads.Voice.TYPE, Payloads.Voice.CODEC, (p, ctx) -> clientVoice.accept(p));
        r.playToClient(Payloads.Welcome.TYPE, Payloads.Welcome.CODEC, (p, ctx) -> clientWelcome.accept(p));
    }

    private static void onHello(Payloads.Hello p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        VoiceHub hub = CitizenVoice.hub();
        UUID uuid = sp.getUUID();
        hub.registerPc(new PcListener(uuid, p.deaf()));
        ctx.reply(new Payloads.Welcome((float) hub.distance(), (float) hub.config().distanceChuchotement));
    }

    private static void onMic(Payloads.Mic p, IPayloadContext ctx) {
        if (p.ulaw().length != Audio.WEB_FRAME || !(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        CitizenVoice.hub().onPcMic(sp.getUUID(), Audio.fromUlaw(p.ulaw()), p.whisper());
    }

    /** Un joueur PC équipé du mod, vu comme un auditeur par le routage. */
    static final class PcListener implements VoiceListener {
        private final UUID uuid;
        private final boolean deaf;

        PcListener(UUID uuid, boolean deaf) {
            this.uuid = uuid;
            this.deaf = deaf;
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
        public boolean isOpen() {
            return CitizenVoice.onlinePlayer(uuid) != null;
        }

        @Override
        public void sendVoice(UUID speaker, float gain, float pan, short[] pcm16, Encoded enc) {
            ServerPlayer p = CitizenVoice.onlinePlayer(uuid);
            if (p != null) {
                PacketDistributor.sendToPlayer(p, new Payloads.Voice(speaker, gain, pan, enc.ulaw()));
            }
        }
    }
}
