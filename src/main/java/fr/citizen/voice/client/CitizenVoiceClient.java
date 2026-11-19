package fr.citizen.voice.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.citizen.voice.Audio;
import fr.citizen.voice.CitizenVoice;
import fr.citizen.voice.net.Payloads;
import fr.citizen.voice.net.VoiceNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/** Partie PC du chat vocal (chargée uniquement dans le jeu du joueur, jamais sur le serveur dédié). */
@Mod(value = CitizenVoice.MODID, dist = Dist.CLIENT)
public final class CitizenVoiceClient {
    static final String CAT = "key.categories.citizenvoice";
    static final KeyMapping MENU = new KeyMapping("key.citizenvoice.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CAT);
    static final KeyMapping PTT = new KeyMapping("key.citizenvoice.ptt", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_CAPS_LOCK, CAT);
    static final KeyMapping WHISPER = new KeyMapping("key.citizenvoice.whisper", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CAT);
    static final KeyMapping MUTE = new KeyMapping("key.citizenvoice.mute", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CAT);

    static ClientConfig config;
    static AudioEngine engine;
    /** Le serveur a répondu : il a le chat vocal. */
    static volatile boolean serverReady;
    static volatile float distance = 20;
    static volatile float whisperDistance = 3;

    public CitizenVoiceClient(IEventBus modBus) {
        config = new ClientConfig(FMLPaths.CONFIGDIR.get().resolve("citizenvoice-client.properties"));
        config.load();
        engine = new AudioEngine(config, CitizenVoiceClient::sendMic, () -> serverReady && Minecraft.getInstance().player != null);
        modBus.addListener(CitizenVoiceClient::onKeys);
        NeoForge.EVENT_BUS.addListener(CitizenVoiceClient::onTick);
        NeoForge.EVENT_BUS.addListener(CitizenVoiceClient::onLogin);
        NeoForge.EVENT_BUS.addListener(CitizenVoiceClient::onLogout);
        NeoForge.EVENT_BUS.addListener(CitizenVoiceClient::onHud);
        VoiceNetwork.clientVoice = p -> engine.enqueue(p.speaker(), p.gain(), p.pan(), Audio.fromUlaw(p.ulaw()));
        VoiceNetwork.clientWelcome = p -> {
            distance = p.distance();
            whisperDistance = p.whisperDistance();
            serverReady = true;
        };
    }

    private static void onKeys(RegisterKeyMappingsEvent e) {
        e.register(MENU);
        e.register(PTT);
        e.register(WHISPER);
        e.register(MUTE);
    }

    /**
     * API publique pour les autres mods Citizen : observe les trames du micro déjà ouvert,
     * après réduction du bruit, sans ouvrir une seconde ligne audio ni les transmettre au réseau.
     * Retourne false si Citizen Voix n'est pas connecté au serveur.
     */
    public static boolean setCommandCapture(java.util.function.Consumer<short[]> listener) {
        if (engine == null || !serverReady || !engine.isRunning()) return false;
        engine.setCommandCapture(listener);
        return true;
    }

    /** Arrête l'observation micro demandée par un autre mod. */
    public static void clearCommandCapture() {
        if (engine != null) engine.setCommandCapture(null);
    }

    private static void sendMic(short[] pcm) {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        if (c != null && serverReady) {
            PacketDistributor.sendToServer(new Payloads.Mic(Audio.toUlaw(pcm), engine.whisperDown));
        }
    }

    static void sendHello() {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        if (c == null) {
            return;
        }
        try {
            if (c.hasChannel(Payloads.Hello.TYPE)) {
                PacketDistributor.sendToServer(new Payloads.Hello(VoiceNetwork.PROTOCOL, config.sourdine));
            }
        } catch (Throwable ignored) {
            // serveur sans Citizen Voix
        }
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        serverReady = false;
        engine.start();
        sendHello();
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        serverReady = false;
        engine.stop();
    }

    private static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        boolean inGame = mc.player != null && mc.screen == null;
        engine.pttDown = inGame && PTT.isDown();
        engine.whisperDown = inGame && WHISPER.isDown();
        while (MENU.consumeClick()) {
            if (mc.player != null) {
                mc.setScreen(new VoiceScreen(mc.screen));
            }
        }
        while (MUTE.consumeClick()) {
            config.microCoupe = !config.microCoupe;
            config.save();
        }
    }

    // ------------------------------------------------------------------ affichage à l'écran

    private static void onHud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!config.afficherHud || mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        GuiGraphics g = e.getGuiGraphics();
        int x = 6;
        int y = 6;
        String status;
        int color;
        if (!serverReady) {
            return;
        } else if (engine.micError != null) {
            status = "Voix : " + engine.micError;
            color = 0xFFD05050;
        } else if (config.microCoupe) {
            status = "Micro coupé (M)";
            color = 0xFFD05050;
        } else if (engine.talking) {
            status = engine.whisperDown ? "Tu chuchotes" : "Tu parles";
            color = 0xFF7BD86A;
        } else {
            status = null;
            color = 0;
        }
        if (config.sourdine) {
            g.drawString(mc.font, "Sourdine", x, y, 0xFFD05050);
            y += 11;
        }
        if (status != null) {
            g.fill(x - 2, y - 2, x + mc.font.width(status) + 12, y + 10, 0x88000000);
            g.fill(x, y + 1, x + 6, y + 7, color);
            g.drawString(mc.font, status, x + 9, y, 0xFFE8CF8A);
            y += 13;
        }
        List<UUID> speaking = engine.speakingNow();
        ClientPacketListener c = mc.getConnection();
        for (UUID u : speaking) {
            PlayerInfo pi = c != null ? c.getPlayerInfo(u) : null;
            String name = pi != null ? pi.getProfile().getName() : "?";
            g.fill(x - 2, y - 2, x + mc.font.width(name) + 12, y + 10, 0x66000000);
            g.fill(x, y + 1, x + 6, y + 7, 0xFFC9A24A);
            g.drawString(mc.font, name, x + 9, y, 0xFFFFFFFF);
            y += 12;
        }
    }
}
