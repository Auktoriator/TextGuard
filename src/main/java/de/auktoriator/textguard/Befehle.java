package de.auktoriator.textguard;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Brigadier-Baum für /textguard (Alias /tg). */
final class Befehle {

    private final TextGuard plugin;
    private final Sichten sichten;
    private final Auffrischen auffrischen;

    Befehle(TextGuard plugin, Sichten sichten, Auffrischen auffrischen) {
        this.plugin = plugin;
        this.sichten = sichten;
        this.auffrischen = auffrischen;
    }

    LiteralCommandNode<CommandSourceStack> baum() {
        var regel = Commands.literal("regel");
        var gruppeArg = gruppeArg();
        for (var art : Art.values()) {
            gruppeArg.then(Commands.literal(art.befehl())
                    .then(Commands.literal("alle").executes(c -> grundsatz(c, art, true)))
                    .then(Commands.literal("keine").executes(c -> grundsatz(c, art, false)))
                    .then(Commands.literal("ausnahme")
                            .then(Commands.literal("hinzufuegen").then(kennzeichenArg(art, false)
                                    .executes(c -> ausnahme(c, art, true))))
                            .then(Commands.literal("entfernen").then(kennzeichenArg(art, true)
                                    .executes(c -> ausnahme(c, art, false))))));
        }
        return Commands.literal("textguard")
                .requires(q -> Sichten.istAdmin(q.getSender(), sichten.ops()))
                .then(Commands.literal("gruppe")
                        .then(Commands.literal("anlegen").then(neueGruppeArg().executes(this::anlegen)))
                        .then(Commands.literal("loeschen").then(gruppeArg().executes(this::loeschen)))
                        .then(Commands.literal("liste").executes(this::liste))
                        .then(Commands.literal("zeigen").then(gruppeArg().executes(this::zeigen))))
                .then(Commands.literal("mitglied")
                        .then(Commands.literal("hinzufuegen").then(gruppeArg().then(spielerArg()
                                .executes(c -> mitglied(c, true)))))
                        .then(Commands.literal("entfernen").then(gruppeArg().then(spielerArg()
                                .executes(c -> mitglied(c, false))))))
                .then(regel.then(gruppeArg))
                .then(Commands.literal("ansicht")
                        .then(Commands.literal("aus").executes(c -> ansicht(c, null)))
                        .then(gruppeArg().executes(c -> ansicht(c, gruppenName(c)))))
                .then(Commands.literal("neuladen").executes(c -> {
                    plugin.neuladen(c.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    // Argumente

    private RequiredArgumentBuilder<CommandSourceStack, String> neueGruppeArg() {
        return Commands.argument("gruppe", StringArgumentType.word());
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> gruppeArg() {
        return Commands.argument("gruppe", StringArgumentType.word()).suggests((c, b) -> {
            sichten.regelwerk().gruppen().keySet().stream()
                    .filter(n -> n.startsWith(b.getRemainingLowerCase())).forEach(b::suggest);
            return b.buildFuture();
        });
    }

    /** Beim Entfernen nur die Ausnahmen der Regel, beim Hinzufügen alle bekannten Kennzeichen. */
    private RequiredArgumentBuilder<CommandSourceStack, String> kennzeichenArg(Art art, boolean nurDieserGruppe) {
        return Commands.argument("kennzeichen", StringArgumentType.word()).suggests((c, b) -> {
            var gruppen = sichten.regelwerk().gruppen();
            var vorschlaege = new TreeSet<String>();
            if (nurDieserGruppe) {
                var gruppe = gruppen.get(c.getArgument("gruppe", String.class));
                if (gruppe != null) {
                    vorschlaege.addAll(gruppe.regel(art).ausnahmen());
                }
            } else {
                gruppen.values().forEach(g -> g.regeln().values().forEach(r -> vorschlaege.addAll(r.ausnahmen())));
            }
            vorschlaege.stream().filter(n -> n.startsWith(b.getRemainingLowerCase())).forEach(b::suggest);
            return b.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, PlayerProfileListResolver> spielerArg() {
        return Commands.argument("spieler", ArgumentTypes.playerProfiles());
    }

    // Befehle

    private int anlegen(CommandContext<CommandSourceStack> c) {
        var name = gruppenName(c);
        if (!Regelwerk.gueltigerName(name)) {
            return fehler(c, "Ungültiger Name. Erlaubt sind a-z, 0-9, _ und -, höchstens 32 Zeichen.");
        }
        if (sichten.regelwerk().gruppen().containsKey(name)) {
            return fehler(c, "Die Gruppe „" + name + "“ gibt es schon.");
        }
        return fertig(c, sichten.regelwerk().mitGruppe(new Gruppe(name, Set.of(), Map.of())),
                "Gruppe „" + name + "“ angelegt.");
    }

    private int loeschen(CommandContext<CommandSourceStack> c) {
        var name = gruppenName(c);
        if (name.equals(Regelwerk.STANDARD)) {
            return fehler(c, "Die Gruppe „standard“ kann nicht gelöscht werden.");
        }
        if (gruppe(c) == null) {
            return 0;
        }
        return fertig(c, sichten.regelwerk().ohneGruppe(name), "Gruppe „" + name + "“ gelöscht.");
    }

    private int liste(CommandContext<CommandSourceStack> c) {
        sichten.regelwerk().gruppen().values().forEach(g -> info(c, g.name() + " (" + g.mitglieder().size() + " Mitglieder)"));
        return Command.SINGLE_SUCCESS;
    }

    private int zeigen(CommandContext<CommandSourceStack> c) {
        var gruppe = gruppe(c);
        if (gruppe == null) {
            return 0;
        }
        var namen = gruppe.mitglieder().stream()
                .map(u -> {
                    var name = Bukkit.getOfflinePlayer(u).getName();
                    return name != null ? name : u.toString();
                })
                .sorted().collect(Collectors.joining(", "));
        var ueberRecht = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("textguard.gruppe." + gruppe.name()))
                .map(Player::getName).sorted().collect(Collectors.joining(", "));
        info(c, "Gruppe " + gruppe.name());
        info(c, "Mitglieder: " + (namen.isEmpty() ? "keine" : namen));
        info(c, "Über Recht (online): " + (ueberRecht.isEmpty() ? "keine" : ueberRecht));
        for (var art : Art.values()) {
            info(c, art.befehl() + ": " + gruppe.regel(art).klartext());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int mitglied(CommandContext<CommandSourceStack> c, boolean hinzufuegen) throws CommandSyntaxException {
        var gruppe = gruppe(c);
        if (gruppe == null) {
            return 0;
        }
        var neu = gruppe;
        var namen = new TreeSet<String>();
        for (var profil : c.getArgument("spieler", PlayerProfileListResolver.class).resolve(c.getSource())) {
            UUID uuid = profil.getId();
            neu = hinzufuegen ? neu.mitMitglied(uuid) : neu.ohneMitglied(uuid);
            namen.add(profil.getName() != null ? profil.getName() : uuid.toString());
        }
        var verb = hinzufuegen ? " hinzugefügt." : " entfernt.";
        return fertig(c, sichten.regelwerk().mitGruppe(neu), String.join(", ", namen) + verb);
    }

    private int grundsatz(CommandContext<CommandSourceStack> c, Art art, boolean alle) {
        var gruppe = gruppe(c);
        if (gruppe == null) {
            return 0;
        }
        var neu = gruppe.mitRegel(art, gruppe.regel(art).mitGrundsatz(alle));
        return fertig(c, sichten.regelwerk().mitGruppe(neu),
                gruppe.name() + ", " + art.befehl() + ": " + neu.regel(art).klartext());
    }

    private int ausnahme(CommandContext<CommandSourceStack> c, Art art, boolean hinzufuegen) {
        var gruppe = gruppe(c);
        if (gruppe == null) {
            return 0;
        }
        var kennzeichen = c.getArgument("kennzeichen", String.class);
        var regel = gruppe.regel(art);
        if (hinzufuegen && !Regelwerk.gueltigerName(kennzeichen)) {
            return fehler(c, "Ungültiges Kennzeichen. Erlaubt sind a-z, 0-9, _ und -, höchstens 32 Zeichen.");
        }
        if (!hinzufuegen && !regel.ausnahmen().contains(kennzeichen)) {
            return fehler(c, "Die Ausnahme „" + kennzeichen + "“ gibt es nicht.");
        }
        var neu = gruppe.mitRegel(art, hinzufuegen ? regel.mitAusnahme(kennzeichen) : regel.ohneAusnahme(kennzeichen));
        return fertig(c, sichten.regelwerk().mitGruppe(neu),
                gruppe.name() + ", " + art.befehl() + ": " + neu.regel(art).klartext());
    }

    private int ansicht(CommandContext<CommandSourceStack> c, String gruppe) {
        if (!(c.getSource().getSender() instanceof Player spieler)) {
            return fehler(c, "Die Testansicht gibt es nur für Spieler.");
        }
        if (gruppe != null && gruppe(c) == null) {
            return 0;
        }
        sichten.testansicht(spieler.getUniqueId(), gruppe);
        sichten.berechne(spieler);
        auffrischen.spieler(spieler);
        info(c, gruppe == null ? "Testansicht beendet." : "Du siehst die Welt jetzt wie die Gruppe „" + gruppe + "“.");
        return Command.SINGLE_SUCCESS;
    }

    // Hilfen

    private static String gruppenName(CommandContext<CommandSourceStack> c) {
        return c.getArgument("gruppe", String.class);
    }

    /** Gibt die Gruppe zurück oder meldet, dass es sie nicht gibt (dann null). */
    private Gruppe gruppe(CommandContext<CommandSourceStack> c) {
        var gruppe = sichten.regelwerk().gruppen().get(gruppenName(c));
        if (gruppe == null) {
            fehler(c, "Die Gruppe „" + gruppenName(c) + "“ gibt es nicht.");
        }
        return gruppe;
    }

    private int fertig(CommandContext<CommandSourceStack> c, Regelwerk neu, String meldung) {
        try {
            plugin.setzen(neu);
        } catch (IOException e) {
            return fehler(c, "Speichern fehlgeschlagen, nichts geändert: " + e.getMessage());
        }
        c.getSource().getSender().sendMessage(Component.text(meldung, NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private static int fehler(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().getSender().sendMessage(Component.text(text, NamedTextColor.RED));
        return 0;
    }

    private static void info(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().getSender().sendMessage(Component.text(text, NamedTextColor.GRAY));
    }
}
