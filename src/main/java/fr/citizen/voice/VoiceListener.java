package fr.citizen.voice;

import java.util.UUID;

/** Quelqu'un qui peut entendre : un navigateur (console/téléphone) ou un joueur PC avec le mod. */
public interface VoiceListener {
    UUID listenerUuid();

    boolean isDeaf();

    boolean isOpen();

    /**
     * @param pcm16 PCM 16 kHz, 320 échantillons
     */
    void sendVoice(UUID speaker, float gain, float pan, short[] pcm16, Encoded enc);

    /** Encodages calculés une seule fois par trame, partagés entre tous les auditeurs. */
    final class Encoded {
        private final short[] pcm;
        private byte[] le16;
        private byte[] ulaw;

        public Encoded(short[] pcm) {
            this.pcm = pcm;
        }

        public synchronized byte[] le16() {
            if (le16 == null) {
                le16 = Audio.shortsToBytes(pcm);
            }
            return le16;
        }

        public synchronized byte[] ulaw() {
            if (ulaw == null) {
                ulaw = Audio.toUlaw(pcm);
            }
            return ulaw;
        }
    }
}
