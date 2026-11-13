package fr.citizen.voice;

import com.mojang.logging.LogUtils;
import fr.citizen.voice.net.VoiceNetwork;
import fr.citizen.voice.web.WebServer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Citizen Voix : chat vocal de proximité du serveur Citizen.
 * Joueurs PC : ce mod installé dans le jeu. Joueurs console / téléphone : page web servie par ce mod.
 */
@Mod(CitizenVoice.MODID)
public final class CitizenVoice {
    public static final String MODID = "citizenvoice";
    private static final Logger LOG = LogUtils.getLogger();

    private static VoiceConfig config;
    private static VoiceHub hub;
    private static WebServer web;
    private static ScheduledExecutorService ticker;

    /** Joueurs en ligne, lisibles depuis les threads audio. */
    private static final Map<UUID, ServerPlayer> ONLINE = new ConcurrentHashMap<>();
    /** Joueurs à qui envoyer le code automatiquement (ticks restants). */
    private static final Map<UUID, Integer> JOIN_DELAY = new ConcurrentHashMap<>();
    private static Method bedrockCheck;
    private static int tick;

    public CitizenVoice(IEventBus modBus, ModContainer container) {
        VoiceLog.setSink((lvl, msg) -> {
            switch (lvl) {
                case "WARN" -> LOG.warn(msg);
                case "ERROR" -> LOG.error(msg);
                default -> LOG.info(msg);
            }
        });
        config = new VoiceConfig(FMLPaths.CONFIGDIR.get().resolve("citizenvoice").resolve("citizenvoice.properties"));
        hub = new VoiceHub(config);
        modBus.addListener(VoiceNetwork::register);
        NeoForge.EVENT_BUS.addListener(CitizenVoice::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CitizenVoice::onServerStopping);
        NeoForge.EVENT_BUS.addListener(CitizenVoice::onTick);
        NeoForge.EVENT_BUS.addListener(CitizenVoice::onLogin);
        NeoForge.EVENT_BUS.addListener(CitizenVoice::onLogout);
        NeoForge.EVENT_BUS.addListener(CitizenVoice::onRespawn);
        NeoForge.EVENT_BUS.addListener(CitizenVoice::onCommands);
        if (ModList.get().isLoaded("citizenbedrock")) {
            try {
                bedrockCheck = Class.forName("fr.citizen.bedrock.BedrockPlayers").getMethod("isBedrock", UUID.class);
            } catch (Throwable t) {
                LOG.warn("Citizen Bedrock présent mais API introuvable : {}", t.toString());
            }
        } else {
            LOG.warn("Citizen Bedrock absent : aucun joueur console détecté, le code ne sera envoyé qu'avec /voix code.");
        }
    }

    public static VoiceHub hub() {
        return hub;
    }

    public static ServerPlayer onlinePlayer(UUID uuid) {
        return ONLINE.get(uuid);
    }

    // ------------------------------------------------------------------ cycle de vie

    private static void onServerStarted(ServerStartedEvent e) {
        config.load();
        startWeb();
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "citizenvoix-state");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(() -> {
            try {
                hub.pushState();
            } catch (Throwable t) {
                LOG.error("pushState", t);
            }
        }, 1, 250, TimeUnit.MILLISECONDS);
    }

    private static void startWeb() {
        web = new WebServer(hub);
        try {
            web.start();
        } catch (Exception ex) {
            LOG.error("Impossible de démarrer le chat vocal web sur le port {} : {}", config.port, ex.toString());
            web = null;
        }
    }

    static boolean reload() {
        config.load();
        hub.disconnectAll();
        if (web != null) {
            web.stop();
        }
        startWeb();
        return web != null;
    }

    private static void onServerStopping(ServerStoppingEvent e) {
        hub.disconnectAll();
        if (web != null) {
            web.stop();
        }
        if (ticker != null) {
            ticker.shutdownNow();
        }
        ONLINE.clear();
        Players.clear();
    }

    // ------------------------------------------------------------------ joueurs

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            ONLINE.put(p.getUUID(), p);
            if (config.messageAutomatique) {
                JOIN_DELAY.put(p.getUUID(), 200); // 10 s après l'arrivée
            }
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            ONLINE.put(p.getUUID(), p); // nouvel objet joueur après une mort
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID u = e.getEntity().getUUID();
        ONLINE.remove(u);
        JOIN_DELAY.remove(u);
        Players.remove(u);
        hub.unregisterPc(u);
    }

    private static void onTick(ServerTickEvent.Post e) {
        MinecraftServer srv = e.getServer();
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            seen.add(p.getUUID());
            ONLINE.put(p.getUUID(), p);
            Players.put(new Players.Snap(p.getUUID(), p.getGameProfile().getName(),
                    p.level().dimension().location().toString(), p.getX(), p.getEyeY(), p.getZ(),
                    p.getYHeadRot(), p.isSpectator()));
        }
        Players.all().removeIf(s -> !seen.contains(s.uuid()));
        ONLINE.keySet().removeIf(u -> !seen.contains(u));

        if (++tick % 20 == 0 && !JOIN_DELAY.isEmpty()) {
            for (Map.Entry<UUID, Integer> en : JOIN_DELAY.entrySet()) {
                int left = en.getValue() - 20;
                if (left > 0) {
                    en.setValue(left);
                    continue;
                }
                JOIN_DELAY.remove(en.getKey());
                ServerPlayer p = srv.getPlayerList().getPlayer(en.getKey());
                if (p != null && isConsole(p.getUUID()) && !hub.isConnected(p.getUUID())) {
                    sendCode(p);
                }
            }
        }
    }

    /**
     * Joueur Bedrock (Xbox, PlayStation, Switch, téléphone, tablette) détecté par Citizen Bedrock.
     * Les joueurs Java PC n'utilisent jamais la page web : ils ont le mod dans leur jeu.
     */
    static boolean isConsole(UUID uuid) {
        if (bedrockCheck != null) {
            try {
                return (Boolean) bedrockCheck.invoke(null, uuid);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ message avec le code

    /** Message pour un joueur Java PC qui tape /voix. */
    static void sendPcInfo(ServerPlayer p) {
        p.sendSystemMessage(Component.literal("§6§l⚜ Chat vocal de proximité ⚜"));
        if (hub.hasPcMod(p.getUUID())) {
            p.sendSystemMessage(Component.literal("§fLe chat vocal est actif : les joueurs à moins de §e"
                    + Math.round(hub.distance()) + " blocs§f t'entendent (§e" + Math.round(config.distanceChuchotement)
                    + " blocs§f en chuchotant). Touche §eV§f pour les réglages (micro, sortie son, volumes)."));
        } else {
            p.sendSystemMessage(Component.literal("§fIl te manque le mod §eCitizen Voix§f dans ton jeu. "
                    + "Mets à jour tes mods Citizen puis relance le jeu."));
        }
    }

    static void sendCode(ServerPlayer p) {
        String code = hub.createCode(p.getUUID(), p.getGameProfile().getName());
        String url = config.publicUrl();
        String shown = url.replaceFirst("^https?://", "");
        MutableComponent head = Component.literal("§6§l⚜ Chat vocal de proximité ⚜");
        MutableComponent l1 = Component.literal("§f1. Sur ton téléphone, ouvre : ")
                .append(Component.literal(shown).withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url + "/?code=" + code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Ouvrir le chat vocal")))));
        MutableComponent l2 = Component.literal("§f2. Entre le code : §e§l" + code + " §7(valable " + config.dureeCodeMinutes + " min)");
        p.sendSystemMessage(head);
        p.sendSystemMessage(l1);
        p.sendSystemMessage(l2);
        if (config.https && config.certificat.isEmpty()) {
            p.sendSystemMessage(Component.literal("§7Première fois : si le navigateur affiche un avertissement, touche "
                    + "« Paramètres avancés » puis « Continuer vers le site »."));
        }
        p.sendSystemMessage(Component.literal("§7Garde la page ouverte pendant que tu joues. Nouveau code : §f/voix"));
    }

    private static void onCommands(RegisterCommandsEvent e) {
        VoiceCommand.register(e.getDispatcher());
    }
}
