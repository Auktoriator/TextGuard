package de.auktoriator.textguard;

import io.papermc.paper.event.player.PlayerOpenSignEvent;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.plugin.Plugin;

/**
 * Schutz vor Datenverlust: Wer einen Text nur als Runen sieht, darf ihn nicht bearbeiten, sonst schickt sein Client die
 * Runen zurück und der Server speichert sie. Creative-Items schützt der Paketlauscher.
 */
final class Schutz implements Listener {

    private static final Component GESPERRT = Component.text(
            "Dieses Schild kannst du nicht lesen und deshalb auch nicht bearbeiten.", NamedTextColor.RED);
    private static final Component BUCH_GESPERRT = Component.text(
            "Dieses Buch kannst du nicht lesen und deshalb auch nicht bearbeiten.", NamedTextColor.RED);

    private final Plugin plugin;
    private final Sichten sichten;
    private final Kennzeichen kennzeichen;

    Schutz(Plugin plugin, Sichten sichten, Kennzeichen kennzeichen) {
        this.plugin = plugin;
        this.sichten = sichten;
        this.kennzeichen = kennzeichen;
    }

    /** Buch und Feder: bearbeiten und signieren. Ein leeres Buch bleibt beschreibbar. */
    @EventHandler(ignoreCancelled = true)
    public void beimBuch(PlayerEditBookEvent event) {
        var alt = event.getPreviousBookMeta();
        var spieler = event.getPlayer();
        var sicht = sichten.sicht(spieler.getUniqueId());
        var kz = Kennzeichen.lesen(alt.getPersistentDataContainer());
        // Gleiche Entscheidung wie im Paketlauscher: beschädigte Kennzeichen sperren.
        if (alt.pages().stream().anyMatch(Schutz::hatText) && !sicht.allesLesbar(Art.BUECHER)
                && (kz == null || !sicht.darfLesen(Art.BUECHER, kz))) {
            event.setCancelled(true);
            spieler.sendMessage(BUCH_GESPERRT);
            // Der Client zeigt sonst seine eigenen Änderungen, bis das Buch neu gesendet wird.
            Bukkit.getScheduler().runTask(plugin, spieler::updateInventory);
            return;
        }
        var neu = event.getNewBookMeta();
        if (event.isSigning() && kz != null && !kz.isEmpty()
                && Set.of().equals(Kennzeichen.lesen(neu.getPersistentDataContainer()))) {
            Kennzeichen.schreiben(neu.getPersistentDataContainer(), kz);
            event.setNewBookMeta(neu);
        }
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
        return seite.lines().stream().anyMatch(Schutz::hatText);
    }

    private static boolean hatText(Component text) {
        return !PlainTextComponentSerializer.plainText().serialize(text).isEmpty();
    }
}
