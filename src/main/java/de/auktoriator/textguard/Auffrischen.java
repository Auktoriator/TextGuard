package de.auktoriator.textguard;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.bukkit.Bukkit;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.Campfire;
import org.bukkit.block.Shelf;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Bringt Spieler nach einer Änderung auf den aktuellen Stand. Nur im Hauptthread aufrufen. */
public final class Auffrischen {

    private final Plugin plugin;
    private final Sichten sichten;
    private final Kennzeichen kennzeichen;
    private final Map<UUID, Boolean> admins = new ConcurrentHashMap<>();

    Auffrischen(Plugin plugin, Sichten sichten, Kennzeichen kennzeichen) {
        this.plugin = plugin;
        this.sichten = sichten;
        this.kennzeichen = kennzeichen;
    }

    /** Berechnet die Sichten aller Online-Spieler neu, bei nurGeaenderte nur diese auffrischen. */
    public void alle(boolean nurGeaenderte) {
        for (var spieler : Bukkit.getOnlinePlayers()) {
            if (sichten.berechne(spieler) || !nurGeaenderte) {
                spieler(spieler);
            } else {
                adminAbgleichen(spieler);
            }
        }
    }

    public void spieler(Player spieler) {
        adminAbgleichen(spieler);
        kennzeichen.nachsenden(spieler);
        kennzeichen.namenNachsenden(spieler);
        spieler.updateInventory();
        buecherNachsenden(spieler);
    }

    public void entfernen(UUID spieler) {
        admins.remove(spieler);
    }

    // Die Befehle hängen am Admin-Recht, der Client kennt den Baum aber nur vom letzten Senden.
    private void adminAbgleichen(Player spieler) {
        var admin = Sichten.istAdmin(spieler, sichten.ops());
        var vorher = admins.put(spieler.getUniqueId(), admin);
        if (vorher != null && vorher != admin) {
            spieler.updateCommands();
        }
    }

    /** Bücher, die der Spieler außerhalb seines Inventars sieht: Itemrahmen, Items am Boden, Regale, … */
    private void buecherNachsenden(Player spieler) {
        for (var entity : spieler.getWorld().getEntities()) {
            var item = switch (entity) {
                case ItemFrame rahmen -> rahmen.getItem();
                case Item boden -> boden.getItemStack();
                case ItemDisplay anzeige -> anzeige.getItemStack();
                default -> null;
            };
            // Neu spawnen schickt alle Metadaten. Beim eigenen Fahrzeug und den Passagieren würde das den Spieler absetzen.
            if (item != null && enthaeltBuch(item) && entity.getTrackedBy().contains(spieler)
                    && entity != spieler.getVehicle() && !spieler.getPassengers().contains(entity)) {
                spieler.hideEntity(plugin, entity);
                spieler.showEntity(plugin, entity);
            }
        }
        for (var chunk : spieler.getWorld().getLoadedChunks()) {
            if (!spieler.isChunkSent(chunk.getChunkKey())) {
                continue;
            }
            for (var zustand : chunk.getTileEntities(false)) {
                List<ItemStack> items = switch (zustand) {
                    case Shelf regal -> Arrays.asList(regal.getInventory().getContents());
                    case Campfire feuer -> IntStream.range(0, feuer.getSize()).mapToObj(feuer::getItem).toList();
                    case BrushableBlock sand -> Arrays.asList(sand.getItem());
                    default -> List.of();
                };
                if (items.stream().filter(Objects::nonNull).anyMatch(Auffrischen::enthaeltBuch)) {
                    kennzeichen.senden(spieler, zustand.getBlock());
                }
            }
        }
    }

    /** Auch verschachtelt in Shulkerkiste, Bündel oder Armbrust. */
    private static boolean enthaeltBuch(ItemStack item) {
        if (item.hasData(DataComponentTypes.WRITTEN_BOOK_CONTENT) || item.hasData(DataComponentTypes.WRITABLE_BOOK_CONTENT)) {
            return true;
        }
        var innen = new ArrayList<ItemStack>();
        var kiste = item.getData(DataComponentTypes.CONTAINER);
        if (kiste != null) {
            innen.addAll(kiste.contents());
        }
        var buendel = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (buendel != null) {
            innen.addAll(buendel.contents());
        }
        var pfeile = item.getData(DataComponentTypes.CHARGED_PROJECTILES);
        if (pfeile != null) {
            innen.addAll(pfeile.projectiles());
        }
        return innen.stream().anyMatch(Auffrischen::enthaeltBuch);
    }
}
