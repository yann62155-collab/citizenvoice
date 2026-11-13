package fr.citizen.voice.client;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Réglages du joueur PC : config/citizenvoice-client.properties */
public final class ClientConfig implements AudioEngine.Settings {
    public String micro = "";
    public String sortie = "";
    public boolean appuyerPourParler = false;
    /** 0..100 : plus c'est haut, plus le micro s'ouvre facilement. */
    public int sensibilite = 70;
    /** Amplification du micro en %. */
    public int gainMicro = 100;
    /** Volume des autres joueurs en %. */
    public int volume = 100;
    public boolean microCoupe = false;
    public boolean sourdine = false;
    public boolean afficherHud = true;

    private final Path file;

    public ClientConfig(Path file) {
        this.file = file;
    }

    public void load() {
        if (!Files.exists(file)) {
            save();
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Exception ignored) {
        }
        micro = p.getProperty("micro", micro);
        sortie = p.getProperty("sortie", sortie);
        appuyerPourParler = Boolean.parseBoolean(p.getProperty("appuyer_pour_parler", "" + appuyerPourParler));
        sensibilite = i(p, "sensibilite", sensibilite);
        gainMicro = i(p, "gain_micro", gainMicro);
        volume = i(p, "volume", volume);
        microCoupe = Boolean.parseBoolean(p.getProperty("micro_coupe", "" + microCoupe));
        sourdine = Boolean.parseBoolean(p.getProperty("sourdine", "" + sourdine));
        afficherHud = Boolean.parseBoolean(p.getProperty("afficher_hud", "" + afficherHud));
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("micro", micro);
        p.setProperty("sortie", sortie);
        p.setProperty("appuyer_pour_parler", "" + appuyerPourParler);
        p.setProperty("sensibilite", "" + sensibilite);
        p.setProperty("gain_micro", "" + gainMicro);
        p.setProperty("volume", "" + volume);
        p.setProperty("micro_coupe", "" + microCoupe);
        p.setProperty("sourdine", "" + sourdine);
        p.setProperty("afficher_hud", "" + afficherHud);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "Citizen Voix - reglages du joueur (modifiables en jeu avec la touche V)");
            }
        } catch (Exception ignored) {
        }
    }

    @Override public String micro() { return micro; }
    @Override public String sortie() { return sortie; }
    @Override public boolean appuyerPourParler() { return appuyerPourParler; }
    @Override public int sensibilite() { return sensibilite; }
    @Override public int gainMicro() { return gainMicro; }
    @Override public int volume() { return volume; }
    @Override public boolean microCoupe() { return microCoupe; }
    @Override public boolean sourdine() { return sourdine; }

    private static int i(Properties p, String k, int d) {
        try {
            return Integer.parseInt(p.getProperty(k, "" + d).trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }
}
