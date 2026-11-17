package de.auktoriator.textguard;

import io.papermc.paper.event.player.PlayerOpenSignEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

/**
 * Schutz vor Datenverlust: Wer einen Text nur als Runen sieht, darf ihn nicht bearbeiten, sonst schickt sein Client die
 * Runen zurück und der Server speichert sie.
 */
final class Schutz implements Listener {

    private static final Component GESPERRT = Component.text(
            "Dieses Schild kannst du nicht lesen und deshalb auch nicht bearbeiten.", NamedTextColor.RED);

    private final Sichten sichten;
    private final Kennzeichen kennzeichen;

    Schutz(Sichten sichten, Kennzeichen kennzeichen) {
        this.sichten = sichten;
        this.kennzeichen = kennzeichen;
    }

    @EventHandler(ignoreCancelled = true)
    public void beimOeffnen(PlayerOpenSignEvent event) {
        var schild = event.getSign();
        if (!hatText(schild.getSide(event.getSide()))) {
            return;
        }
        var spieler = event.getPlayer();
        kennzeichen.nachlesen(schild.getBlock());
        if (!lesbar(spieler, schild.getBlock())) {
            event.setCancelled(true);
            spieler.sendMessage(GESPERRT);
            return;
        }
        // Zeigt der Client noch Runen (Chunk eben erst durchgesehen), kommt so der Klartext vor dem Editor an.
        kennzeichen.senden(spieler, schild.getBlock());
    }

    /** Zweites Netz, falls der Editor auf anderem Weg offen ist. */
    @EventHandler(ignoreCancelled = true)
    public void beimAendern(SignChangeEvent event) {
        if (event.getBlock().getState() instanceof Sign schild && hatText(schild.getSide(event.getSide()))
                && !lesbar(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(GESPERRT);
        }
    }

    // Gleiche Entscheidung wie im Paketlauscher, damit sie zu dem passt, was der Client anzeigt.
    private boolean lesbar(Player spieler, Block block) {
        var sicht = sichten.sicht(spieler.getUniqueId());
        if (sicht.allesLesbar(Art.SCHILDER)) {
            return true;
        }
        var kz = kennzeichen.schild(spieler.getUniqueId(), block.getX(), block.getY(), block.getZ());
        return kz != null && sicht.darfLesen(Art.SCHILDER, kz);
    }

    private static boolean hatText(SignSide seite) {
        return seite.lines().stream().anyMatch(z -> !PlainTextComponentSerializer.plainText().serialize(z).isEmpty());
    }
}
