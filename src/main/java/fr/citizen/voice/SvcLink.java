package fr.citizen.voice;

import java.util.UUID;

/** Pont vers Simple Voice Chat (implémenté dans le paquet svc, seulement si le mod voicechat est présent). */
public interface SvcLink {
    /** Joue la voix d'un joueur web (PCM 48 kHz, 960 échantillons) à la position de son personnage, pour les joueurs PC. */
    void sendWebVoice(UUID player, short[] pcm48);

    /** Le joueur web a arrêté de parler / s'est déconnecté. */
    void stopWebVoice(UUID player);

    /** Distance de voix configurée dans Simple Voice Chat. */
    double defaultDistance();

    /** true si ce joueur a Simple Voice Chat installé et connecté sur son PC. */
    boolean hasModInstalled(UUID player);
}
