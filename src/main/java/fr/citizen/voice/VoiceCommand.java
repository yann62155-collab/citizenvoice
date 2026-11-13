package fr.citizen.voice;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

/**
 * /voix                      : joueur console -> code pour la page web ; joueur PC -> rappel des touches
 * /voix statut               : (admin) qui est connecté par le navigateur
 * /voix code <joueurs>       : (admin) envoie un nouveau code à des joueurs et te l'affiche
 * /voix voir <joueur>        : (admin) affiche le code d'un joueur sans le lui envoyer
 * /voix codes                : (admin) liste tous les codes encore valables
 * /voix deconnecter <joueur> : (admin) coupe la voix web d'un joueur
 * /voix recharger            : (admin) relit la config et redémarre le site
 */
final class VoiceCommand {
    private VoiceCommand() {
    }

    /** « Pseudo : 123456 » ; cliquer sur le code le copie. */
    private static Component codeLine(String name, String code, String note) {
        return Component.literal("§6[Voix] §f" + name + " : ")
                .append(Component.literal("§e§l" + code).withStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Cliquer pour copier")))))
                .append(Component.literal(" §7(" + note + ")"));
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
                            + " §f| Distance : §e" + Math.round(hub.distance()) + " blocs §f(chuchotement §e"
                            + Math.round(hub.config().distanceChuchotement) + "§f) | Joueurs PC avec le mod : §e" + hub.pcCount()), false);
                    c.getSource().sendSuccess(() -> Component.literal("§6[Voix] §f" + l.size() + " joueur(s) via navigateur"
                            + (l.isEmpty() ? "" : " : §e" + String.join("§f, §e", l))), false);
                    return l.size();
                }))
                .then(Commands.literal("code").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("joueurs", EntityArgument.players()).executes(c -> {
                            Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "joueurs");
                            for (ServerPlayer p : ps) {
                                String code = CitizenVoice.sendCode(p);
                                c.getSource().sendSuccess(() -> codeLine(p.getGameProfile().getName(), code, "envoyé au joueur"), true);
                            }
                            return ps.size();
                        })))
                .then(Commands.literal("voir").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("joueur", EntityArgument.player()).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "joueur");
                            VoiceHub hub = CitizenVoice.hub();
                            String code = hub.currentCode(p.getUUID());
                            String note = "code en cours";
                            if (code == null) {
                                code = hub.createCode(p.getUUID(), p.getGameProfile().getName());
                                note = "nouveau code, visible par toi seul";
                            }
                            String fc = code;
                            String fn = note;
                            c.getSource().sendSuccess(() -> codeLine(p.getGameProfile().getName(), fc, fn), false);
                            c.getSource().sendSuccess(() -> Component.literal("§7Adresse : §e" + hub.config().publicUrl()), false);
                            return 1;
                        })))
                .then(Commands.literal("codes").requires(s -> s.hasPermission(2)).executes(c -> {
                    List<String[]> l = CitizenVoice.hub().activeCodes();
                    c.getSource().sendSuccess(() -> Component.literal("§6[Voix] §f" + l.size() + " code(s) valable(s)"
                            + (l.isEmpty() ? "." : " :")), false);
                    for (String[] e : l) {
                        c.getSource().sendSuccess(() -> codeLine(e[0], e[1], "encore " + e[2] + " min"), false);
                    }
                    return l.size();
                }))
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
