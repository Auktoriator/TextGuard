package de.auktoriator.textguard;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.WeakHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Diamanthacke wie der Debug-Stick: Linksklick wählt das nächste Kennzeichen, Rechtsklick auf Schild oder Mob schaltet
 * es dort an oder aus. Das gewählte Kennzeichen steht im PDC der Hacke. Nur für Admins, sonst ist es eine normale Hacke.
 */
final class Werkzeug implements Listener {

    static final NamespacedKey SCHLUESSEL = Objects.requireNonNull(NamespacedKey.fromString("textguard:werkzeug"));

    private final Sichten sichten;
    private final Kennzeichen kennzeichen;
    /** Tick des letzten Klicks. Der Client meldet manche Klicks doppelt, etwa auf Rüstungsständer. */
    private final Map<Player, Integer> letzterKlick = new WeakHashMap<>();

    Werkzeug(Sichten sichten, Kennzeichen kennzeichen) {
        this.sichten = sichten;
        this.kennzeichen = kennzeichen;
    }

    static ItemStack neu(String kz) {
        var item = ItemStack.of(Material.DIAMOND_HOE);
        item.editMeta(meta -> meta.itemName(Component.text("TextGuard-Werkzeug", NamedTextColor.GOLD)));
        waehlen(item, kz == null ? "" : kz);
        return item;
    }

    static boolean istWerkzeug(ItemStack item) {
        return item != null && item.getType() == Material.DIAMOND_HOE
                && item.getPersistentDataContainer().has(SCHLUESSEL, PersistentDataType.STRING);
    }

    static void waehlen(ItemStack werkzeug, String kz) {
        werkzeug.editPersistentDataContainer(pdc -> pdc.set(SCHLUESSEL, PersistentDataType.STRING, kz));
    }

    private static String gewaehlt(ItemStack werkzeug) {
        return werkzeug.getPersistentDataContainer().getOrDefault(SCHLUESSEL, PersistentDataType.STRING, "");
    }

    /** Das Kennzeichen nach aktuell, nach dem letzten wieder das erste. alle darf nicht leer sein. */
    static String naechstes(SortedSet<String> alle, String aktuell) {
        var danach = alle.tailSet(aktuell + "\0");
        return danach.isEmpty() ? alle.first() : danach.first();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void beimKlick(PlayerInteractEvent event) {
        var spieler = event.getPlayer();
        var werkzeug = event.getHand() == EquipmentSlot.HAND ? inDerHand(spieler) : null;
        if (werkzeug == null) {
            return;
        }
        // Kein Schild-Editor, kein Umgraben, im Creative kein Abbauen.
        event.setCancelled(true);
        if (!ersterKlick(spieler)) {
            return;
        }
        switch (event.getAction()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> weiter(spieler, werkzeug);
            case RIGHT_CLICK_BLOCK -> {
                var ziel = Ziel.schild(Objects.requireNonNull(event.getClickedBlock()), kennzeichen);
                if (ziel == null) {
                    melden(spieler, "Kein Schild. Rechtsklick auf ein Schild oder einen Mob.", NamedTextColor.RED);
                } else {
                    umschalten(spieler, werkzeug, ziel);
                }
            }
            default -> {
                var kz = gewaehlt(werkzeug);
                melden(spieler, "Gewählt: " + (kz.isEmpty() ? "nichts, Linksklick wählt" : kz), NamedTextColor.GRAY);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void beimMob(PlayerInteractEntityEvent event) {
        mob(event);
    }

    // Eigene Handler-Liste, kommt bei beimMob nicht an. Rüstungsständer melden sich nur hier.
    @EventHandler(priority = EventPriority.LOW)
    public void beimMobAn(PlayerInteractAtEntityEvent event) {
        mob(event);
    }

    private void mob(PlayerInteractEntityEvent event) {
        var spieler = event.getPlayer();
        var werkzeug = event.getHand() == EquipmentSlot.HAND ? inDerHand(spieler) : null;
        if (werkzeug == null) {
            return;
        }
        event.setCancelled(true);
        if (!ersterKlick(spieler)) {
            return;
        }
        if (event.getRightClicked() instanceof Player) {
            melden(spieler, "Spieler haben keine Mob-Namen.", NamedTextColor.RED);
        } else {
            umschalten(spieler, werkzeug, Ziel.entity(event.getRightClicked(), kennzeichen));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void beimAngriff(PrePlayerAttackEntityEvent event) {
        var spieler = event.getPlayer();
        var werkzeug = inDerHand(spieler);
        if (werkzeug != null) {
            event.setCancelled(true);
            if (ersterKlick(spieler)) {
                weiter(spieler, werkzeug);
            }
        }
    }

    // Absicherung, falls ein Abbauen am abgebrochenen Klick vorbeikommt: Ein Schild soll nie verloren gehen.
    @EventHandler(priority = EventPriority.LOW)
    public void beimAbbauen(BlockBreakEvent event) {
        if (inDerHand(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    /** Das Werkzeug in der Haupthand eines Admins (ein Spiegel ins Inventar), sonst null. */
    private ItemStack inDerHand(Player spieler) {
        var item = spieler.getInventory().getItemInMainHand();
        return istWerkzeug(item) && Sichten.istAdmin(spieler, sichten.ops()) ? item : null;
    }

    private boolean ersterKlick(Player spieler) {
        var tick = Bukkit.getCurrentTick();
        return !Objects.equals(letzterKlick.put(spieler, tick), tick);
    }

    private void weiter(Player spieler, ItemStack werkzeug) {
        var alle = sichten.regelwerk().kennzeichen();
        if (alle.isEmpty()) {
            melden(spieler, "Keine Kennzeichen in den Regeln. Wähle eins mit /tg werkzeug <kennzeichen>.",
                    NamedTextColor.RED);
            return;
        }
        var kz = naechstes(alle, gewaehlt(werkzeug));
        waehlen(werkzeug, kz);
        melden(spieler, "Gewählt: " + kz, NamedTextColor.AQUA);
    }

    private void umschalten(Player spieler, ItemStack werkzeug, Ziel ziel) {
        var kz = gewaehlt(werkzeug);
        if (kz.isEmpty()) {
            melden(spieler, "Erst mit Linksklick ein Kennzeichen wählen.", NamedTextColor.RED);
            return;
        }
        var vorher = Kennzeichen.lesen(ziel.pdc());
        var an = vorher != null && !vorher.contains(kz);
        // Bei beschädigten Kennzeichen meldet aendern das Problem und ändert nichts.
        var problem = ziel.aendern(kz, an);
        if (problem != null) {
            melden(spieler, problem, NamedTextColor.RED);
            return;
        }
        melden(spieler, ziel.name() + ": " + kz + (an ? " ✔" : " ✘") + " · lesbar für: "
                + ziel.lesbarFuer(sichten.regelwerk()), NamedTextColor.GREEN);
    }

    private static void melden(Player spieler, String text, NamedTextColor farbe) {
        spieler.sendActionBar(Component.text(text, farbe));
    }
}
