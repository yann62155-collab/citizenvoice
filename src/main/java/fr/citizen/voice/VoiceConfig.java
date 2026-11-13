package fr.citizen.voice;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration modifiable à chaud ({@code /voix recharger}) : config/citizenvoice/citizenvoice.properties
 */
public final class VoiceConfig {
    public int port = 30053;
    public String adressePublique = "mc312.boxtoplay.com";
    public boolean https = true;
    /** Fichier .p12 d'un vrai certificat (laisser vide = certificat auto-signé généré automatiquement). */
    public String certificat = "";
    public String certificatMotDePasse = "";
    /** Distance d'écoute en blocs. */
    public double distance = 20;
    /** Distance quand un joueur chuchote. */
    public double distanceChuchotement = 3;
    /** Envoyer automatiquement le code aux joueurs console/mobile (détectés par Citizen Bedrock). */
    public boolean messageAutomatique = true;
    public int dureeCodeMinutes = 10;
    public int dureeSessionHeures = 24;

    private final Path file;

    public VoiceConfig(Path file) {
        this.file = file;
    }

    public Path dir() {
        return file.getParent();
    }

    public String publicUrl() {
        String scheme = https ? "https" : "http";
        boolean defaultPort = (https && port == 443) || (!https && port == 80);
        return scheme + "://" + adressePublique + (defaultPort ? "" : ":" + port);
    }

    public void load() {
        Properties p = new Properties();
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            }
        } catch (IOException e) {
            VoiceLog.error("Lecture de la config impossible", e);
        }
        int version = intOf(p, "version_config", 1);
        port = intOf(p, "port", port);
        adressePublique = p.getProperty("adresse_publique", adressePublique).trim();
        https = Boolean.parseBoolean(p.getProperty("https", String.valueOf(https)).trim());
        certificat = p.getProperty("certificat", certificat).trim();
        certificatMotDePasse = p.getProperty("certificat_mot_de_passe", certificatMotDePasse);
        distance = doubleOf(p, "distance", distance);
        distanceChuchotement = doubleOf(p, "distance_chuchotement", distanceChuchotement);
        messageAutomatique = Boolean.parseBoolean(p.getProperty("message_automatique", String.valueOf(messageAutomatique)).trim());
        dureeCodeMinutes = intOf(p, "duree_code_minutes", dureeCodeMinutes);
        dureeSessionHeures = intOf(p, "duree_session_heures", dureeSessionHeures);
        if (version < 2) {
            // Ancienne config de la 1re version (liée à Simple Voice Chat) : on reprend les nouvelles valeurs
            if (port == 30079) {
                port = 30053;
            }
            if (distance <= 0) {
                distance = 20;
            }
            distanceChuchotement = doubleOf(p, "distance_chuchotement", 3);
            VoiceLog.info("Ancienne configuration mise à jour (port " + port + ", distance " + distance + ")");
        }
        if (distance <= 0) {
            distance = 20;
        }
        if (distanceChuchotement <= 0) {
            distanceChuchotement = 3;
        }
        save();
    }

    public void save() {
        String txt = """
                # Citizen Voix — chat vocal de proximité (mod PC + page web pour console / téléphone)
                # Modifiable à chaud avec /voix recharger
                version_config=2

                # Port du site (doit être ouvert chez l'hébergeur, ex. un port réservé BoxToPlay)
                port=%d
                # Adresse affichée aux joueurs (sans http/https)
                adresse_publique=%s
                # true = https (OBLIGATOIRE pour que les navigateurs autorisent le micro)
                https=%s
                # Vrai certificat au format .p12 (chemin relatif au dossier config/citizenvoice). Vide = auto-signé.
                certificat=%s
                certificat_mot_de_passe=%s
                # Distance d'écoute en blocs (voix normale)
                distance=%s
                # Distance d'écoute quand un joueur chuchote (en blocs)
                distance_chuchotement=%s
                # Envoyer le code automatiquement aux joueurs console/mobile (détectés par Citizen Bedrock)
                message_automatique=%s
                # Durée de validité d'un code (minutes) et d'une session navigateur (heures)
                duree_code_minutes=%d
                duree_session_heures=%d
                """.formatted(port, adressePublique, https, certificat, certificatMotDePasse, distance,
                distanceChuchotement, messageAutomatique, dureeCodeMinutes, dureeSessionHeures);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(txt);
        } catch (IOException e) {
            VoiceLog.error("Écriture de la config impossible", e);
        }
    }

    private static int intOf(Properties p, String k, int def) {
        try {
            return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double doubleOf(Properties p, String k, double def) {
        try {
            return Double.parseDouble(p.getProperty(k, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
