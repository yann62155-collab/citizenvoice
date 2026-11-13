package fr.citizen.voice;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

/**
 * /voix                      : joueur console -> code pour la page web ; joueur PC -> rappel Simple Voice Chat
 * /voix statut               : (admin) qui est connecté par le navigateur
 * /voix code <joueurs>       : (admin) envoie un code à des joueurs
 * /voix deconnecter <joueur> : (admin) coupe la voix web d'un joueur
 * /voix recharger            : (admin) relit la config et redémarre le site
 */
final class VoiceCommand {
    private VoiceCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralCommandNode<CommandSourceStack> root = d.register(Commands.literal("voix")
                .executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (CitizenVoice.isConsole(p.getUUID())) {
                        CitizenVoice.sendCode(p);
                    } else {
                        CitizenVoice.sendPcInfo(p);
                    }
                    return 1;
                })
                .then(Commands.literal("statut").requires(s -> s.hasPermission(2)).executes(c -> {
                    VoiceHub hub = CitizenVoice.hub();
                    List<String> l = hub.connectedNames();
                    c.getSource().sendSuccess(() -> Component.literal("§6[Voix] §fSite : §e" + hub.config().publicUrl()
                            + " §f| Simple Voice Chat : " + (hub.svc() != null ? "§arelié" : "§cabsent")
                            + " §f| Distance : §e" + Math.round(hub.distance()) + " blocs"), false);
                    c.getSource().sendSuccess(() -> Component.literal("§6[Voix] §f" + l.size() + " joueur(s) via navigateur"
                            + (l.isEmpty() ? "" : " : §e" + String.join("§f, §e", l))), false);
                    return l.size();
                }))
                .then(Commands.literal("code").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("joueurs", EntityArgument.players()).executes(c -> {
                            Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "joueurs");
                            ps.forEach(CitizenVoice::sendCode);
                            c.getSource().sendSuccess(() -> Component.literal("§6[Voix] §fCode envoyé à " + ps.size() + " joueur(s)."), true);
                            return ps.size();
                        })))
                .then(Commands.literal("deconnecter").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("joueur", EntityArgument.player()).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "joueur");
                            CitizenVoice.hub().disconnect(p.getUUID(), "Déconnecté par un administrateur.");
                            c.getSource().sendSuccess(() -> Component.literal("§6[Voix] §f" + p.getGameProfile().getName()
                                    + " est déconnecté du chat vocal web."), true);
                            return 1;
                        })))
                .then(Commands.literal("recharger").requires(s -> s.hasPermission(2)).executes(c -> {
                    boolean ok = CitizenVoice.reload();
                    if (ok) {
                        c.getSource().sendSuccess(() -> Component.literal("§6[Voix] §aConfig rechargée, site relancé : §e"
                                + CitizenVoice.hub().config().publicUrl()), true);
                    } else {
                        c.getSource().sendFailure(Component.literal("[Voix] Le site n'a pas pu démarrer (port déjà pris ? voir la console)."));
                    }
                    return ok ? 1 : 0;
                })));
        d.register(Commands.literal("voice").redirect(root).executes(root.getCommand()));
    }
}
