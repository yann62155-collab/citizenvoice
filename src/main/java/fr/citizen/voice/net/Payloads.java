package fr.citizen.voice.net;

import fr.citizen.voice.CitizenVoice;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Paquets échangés entre le mod PC et le serveur (voix compressée en µ-law 16 kHz, 20 ms par paquet). */
public final class Payloads {
    private Payloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CitizenVoice.MODID, path));
    }

    /** Client -> serveur : « j'ai le mod » + état sourdine. */
    public record Hello(int protocol, boolean deaf) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = Payloads.id("hello");
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.protocol);
                    buf.writeBoolean(p.deaf);
                },
                buf -> new Hello(buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> serveur : une trame de micro. */
    public record Mic(byte[] ulaw, boolean whisper) implements CustomPacketPayload {
        public static final Type<Mic> TYPE = Payloads.id("mic");
        public static final StreamCodec<FriendlyByteBuf, Mic> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeByteArray(p.ulaw);
                    buf.writeBoolean(p.whisper);
                },
                buf -> new Mic(buf.readByteArray(2048), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : la voix d'un joueur proche, déjà atténuée (gain) et placée gauche/droite (pan). */
    public record Voice(UUID speaker, float gain, float pan, byte[] ulaw) implements CustomPacketPayload {
        public static final Type<Voice> TYPE = Payloads.id("voice");
        public static final StreamCodec<FriendlyByteBuf, Voice> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.speaker);
                    buf.writeFloat(p.gain);
                    buf.writeFloat(p.pan);
                    buf.writeByteArray(p.ulaw);
                },
                buf -> new Voice(buf.readUUID(), buf.readFloat(), buf.readFloat(), buf.readByteArray(2048)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : réponse au Hello (le serveur a bien le chat vocal) + distances. */
    public record Welcome(float distance, float whisperDistance) implements CustomPacketPayload {
        public static final Type<Welcome> TYPE = Payloads.id("welcome");
        public static final StreamCodec<FriendlyByteBuf, Welcome> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeFloat(p.distance);
                    buf.writeFloat(p.whisperDistance);
                },
                buf -> new Welcome(buf.readFloat(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
