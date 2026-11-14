package fr.citizen.voice.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/** Menu des réglages du chat vocal (touche V). */
public final class VoiceScreen extends Screen {
    private final Screen parent;
    private List<String> inputs;
    private List<String> outputs;
    private int top;
    private int left;
    private static final int W = 300;

    public VoiceScreen(Screen parent) {
        super(Component.literal("Citizen Voix — Réglages du chat vocal"));
        this.parent = parent;
    }

    private static String label(String device) {
        if (device == null || device.isEmpty()) {
            return "Par défaut (Windows)";
        }
        return device.length() > 34 ? device.substring(0, 33) + "…" : device;
    }

    @Override
    protected void init() {
        ClientConfig c = CitizenVoiceClient.config;
        AudioEngine eng = CitizenVoiceClient.engine;
        inputs = AudioEngine.inputs();
        outputs = AudioEngine.outputs();
        inputs.add(0, "");
        outputs.add(0, "");
        left = width / 2 - W / 2;
        top = Math.max(28, height / 2 - 132);
        int y = top;

        addRenderableWidget(Button.builder(Component.literal("Micro : " + label(c.micro)), b -> {
            c.micro = next(inputs, c.micro);
            b.setMessage(Component.literal("Micro : " + label(c.micro)));
            c.save();
            eng.restart();
        }).bounds(left, y, W, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Sortie son : " + label(c.sortie)), b -> {
            c.sortie = next(outputs, c.sortie);
            b.setMessage(Component.literal("Sortie son : " + label(c.sortie)));
            c.save();
            eng.restart();
        }).bounds(left, y, W, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(modeText(c), b -> {
            c.appuyerPourParler = !c.appuyerPourParler;
            b.setMessage(modeText(c));
            c.save();
        }).bounds(left, y, W, 20).build());
        y += 24;
        addRenderableWidget(new Slider(left, y, W, "Sensibilité du micro", c.sensibilite, 100, "%", v -> c.sensibilite = v));
        y += 24;
        addRenderableWidget(new Slider(left, y, W, "Amplification du micro", c.gainMicro, 300, "%", v -> c.gainMicro = v));
        y += 24;
        addRenderableWidget(new Slider(left, y, W, "Volume des autres joueurs", c.volume, 200, "%", v -> c.volume = v));
        y += 24;
        int half = W / 2 - 2;
        addRenderableWidget(Button.builder(muteText(c), b -> {
            c.microCoupe = !c.microCoupe;
            b.setMessage(muteText(c));
            c.save();
        }).bounds(left, y, half, 20).build());
        addRenderableWidget(Button.builder(deafText(c), b -> {
            c.sourdine = !c.sourdine;
            b.setMessage(deafText(c));
            c.save();
            CitizenVoiceClient.sendHello(); // prévient le serveur : inutile de m'envoyer la voix
        }).bounds(left + half + 4, y, half, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(testText(eng), b -> {
            eng.testMic = !eng.testMic;
            b.setMessage(testText(eng));
        }).bounds(left, y, half, 20).build());
        addRenderableWidget(Button.builder(hudText(c), b -> {
            c.afficherHud = !c.afficherHud;
            b.setMessage(hudText(c));
            c.save();
        }).bounds(left + half + 4, y, half, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(noiseText(c), b -> {
            c.reducteurBruit = !c.reducteurBruit;
            b.setMessage(noiseText(c));
            c.save();
        }).bounds(left, y, W, 20).build());
        y += 48;
        addRenderableWidget(Button.builder(Component.literal("Terminé"), b -> onClose()).bounds(width / 2 - 75, y, 150, 20).build());
    }

    private static Component modeText(ClientConfig c) {
        return Component.literal("Mode : " + (c.appuyerPourParler ? "Appuyer pour parler" : "Voix automatique"));
    }

    private static Component muteText(ClientConfig c) {
        return Component.literal(c.microCoupe ? "§cMicro coupé" : "Micro actif");
    }

    private static Component deafText(ClientConfig c) {
        return Component.literal(c.sourdine ? "§cSourdine" : "Son actif");
    }

    private static Component testText(AudioEngine e) {
        return Component.literal(e.testMic ? "§aTest micro : en cours" : "Tester mon micro");
    }

    private static Component noiseText(ClientConfig c) {
        return Component.literal("Réducteur de bruit parasite : " + (c.reducteurBruit ? "§aactivé" : "§cdésactivé"));
    }

    private static Component hudText(ClientConfig c) {
        return Component.literal("Indicateurs : " + (c.afficherHud ? "oui" : "non"));
    }

    private static String next(List<String> list, String cur) {
        int i = list.indexOf(cur);
        return list.get((i + 1) % list.size());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, top - 18, 0xFFC9A24A);
        AudioEngine eng = CitizenVoiceClient.engine;
        ClientConfig c = CitizenVoiceClient.config;
        int y = top + 24 * 9;
        // vu-mètre du micro + seuil de déclenchement
        g.fill(left, y, left + W, y + 8, 0xFF2B1D12);
        int w = (int) (W * eng.level);
        g.fill(left, y, left + w, y + 8, eng.talking ? 0xFF5F9E4A : 0xFFC9A24A);
        if (!c.appuyerPourParler) {
            int t = (int) (W * (1 - c.sensibilite / 100f) * 0.8f);
            g.fill(left + t, y - 2, left + t + 1, y + 10, 0xFFA32B2B);
        }
        String info = eng.micError != null ? "§c" + eng.micError
                : eng.outError != null ? "§c" + eng.outError
                : CitizenVoiceClient.serverReady
                ? "§7Portée : " + Math.round(CitizenVoiceClient.distance) + " blocs, chuchotement (B) : "
                + Math.round(CitizenVoiceClient.whisperDistance) + " blocs. Parler : " + (c.appuyerPourParler ? "Verr. Maj" : "automatique")
                : "§7Ce serveur n'a pas le chat vocal Citizen.";
        g.drawCenteredString(font, Component.literal(info), width / 2, y + 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        CitizenVoiceClient.engine.testMic = false;
        CitizenVoiceClient.config.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class Slider extends AbstractSliderButton {
        private final String name;
        private final int max;
        private final String unit;
        private final IntConsumer set;

        Slider(int x, int y, int w, String name, int value, int max, String unit, IntConsumer set) {
            super(x, y, w, 20, Component.empty(), value / (double) max);
            this.name = name;
            this.max = max;
            this.unit = unit;
            this.set = set;
            updateMessage();
        }

        private int intValue() {
            return (int) Math.round(value * max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(name + " : " + intValue() + unit));
        }

        @Override
        protected void applyValue() {
            set.accept(intValue());
        }
    }
}
