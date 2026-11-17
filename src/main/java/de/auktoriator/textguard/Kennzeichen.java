package de.auktoriator.textguard;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.Optional;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Kennzeichen an Schildern und Entities. Sie stehen im PDC. Paper schickt das PDC nicht an den Client, und der
 * Paketlauscher darf nicht auf die Welt zugreifen, deshalb gibt es Zwischenspeicher: pro geladenem Chunk für Schilder,
 * pro Entity-ID für Entities mit Kennzeichen. Bei Büchern reist das PDC im custom_data des Items mit, der Lauscher liest
 * es direkt aus dem Paket.
 */
final class Kennzeichen implements Listener {

    static final NamespacedKey SCHLUESSEL = Objects.requireNonNull(NamespacedKey.fromString("textguard:kennzeichen"));

    private record ChunkOrt(UUID welt, long chunk) {
    }

    private final Plugin plugin;
    /** Pro durchgesehenem Chunk jedes Schild mit lesbarem Eintrag. Unveränderliche Werte, nur ganz austauschen. */
    private final Map<ChunkOrt, Map<Vector3i, Set<String>>> chunks = new ConcurrentHashMap<>();
    /** Chunks, in denen der Lauscher ein unbekanntes Schild verschleiert hat: durchsehen und nachsenden. */
    private final Set<ChunkOrt> unbekannt = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> welten = new ConcurrentHashMap<>();
    /** Marker für ein beschädigtes Kennzeichen-PDC. Wird per Identität verglichen, der Inhalt trifft nie eine Ausnahme. */
    private static final Set<String> DEFEKT = Set.of("!defekt");
    /** Nur Entities mit Kennzeichen. Was fehlt, hat keine (auch Fake-Entities anderer Plugins). */
    private final Map<Integer, Set<String>> entities = new ConcurrentHashMap<>();
    /** Entity-IDs von Spielern: Ihre Metadaten fasst der Lauscher nie an. */
    private final Set<Integer> spielerIds = ConcurrentHashMap.newKeySet();

    Kennzeichen(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Netty-sicher. Kennzeichen des Schilds bei x y z in der Welt des Spielers, oder null, wenn unbekannt. Dann muss
     * der Aufrufer verschleiern, und der Chunk wird im nächsten Tick durchgesehen und nachgesendet.
     */
    Set<String> schild(UUID spieler, int x, int y, int z) {
        var welt = welten.get(spieler);
        if (welt == null) {
            return null;
        }
        var ort = new ChunkOrt(welt, Chunk.getChunkKey(x >> 4, z >> 4));
        var schilder = chunks.get(ort);
        var kennzeichen = schilder == null ? null : schilder.get(new Vector3i(x, y, z));
        if (kennzeichen == null) {
            unbekannt.add(ort);
        }
        return kennzeichen;
    }

    /** Netty-sicher. */
    boolean istSpieler(int entityId) {
        return spielerIds.contains(entityId);
    }

    /** Netty-sicher. Darf die Sicht den Namen der Entity lesen? Unbekannte IDs haben keine Kennzeichen. */
    boolean mobnameLesbar(Sicht sicht, int entityId) {
        var kz = entities.get(entityId);
        return kz != DEFEKT && sicht.darfLesen(Art.MOBNAMEN, kz == null ? Set.of() : kz);
    }

    // Alles Weitere nur im Hauptthread.

    /** Liest die Kennzeichen der Entity neu in den Zwischenspeicher. */
    void entityNachlesen(Entity entity) {
        if (entity instanceof Player) {
            spielerIds.add(entity.getEntityId());
            return;
        }
        var kz = lesen(entity.getPersistentDataContainer());
        if (kz == null) {
            plugin.getLogger().warning("Kennzeichen an " + entity.getType().key().value() + " " + entity.getUniqueId()
                    + " nicht lesbar, der Name bleibt für alle mit Einschränkungen verschleiert.");
        }
        entityEintragen(entity.getEntityId(), kz);
    }

    /** kz null: beschädigt, dann gesperrt. Leer: kein Eintrag. */
    void entityEintragen(int entityId, Set<String> kz) {
        if (kz == null) {
            entities.put(entityId, DEFEKT);
        } else if (kz.isEmpty()) {
            entities.remove(entityId);
        } else {
            entities.put(entityId, kz);
        }
    }

    /** Schickt den echten Namen an alle, die die Entity verfolgen. Der Lauscher verschleiert passend. */
    void nameSenden(Entity entity) {
        entity.getTrackedBy().forEach(spieler -> nameSenden(spieler, entity));
    }

    private static void nameSenden(Player spieler, Entity entity) {
        var name = entity.customName();
        if (name != null) {
            // Nicht silent: Das Paket muss durch den Lauscher.
            PacketEvents.getAPI().getPlayerManager().getUser(spieler).sendPacket(new WrapperPlayServerEntityMetadata(
                    entity.getEntityId(), List.of(new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                            Optional.of(name)))));
        }
    }

    /** Schickt dem Spieler die Namen aller benannten Entities, die er verfolgt. */
    void namenNachsenden(Player spieler) {
        spieler.getWorld().getEntities().stream()
                .filter(e -> !(e instanceof Player) && e.customName() != null && e.getTrackedBy().contains(spieler))
                .forEach(e -> nameSenden(spieler, e));
    }

    /** Beim Pluginstart: Entities, die schon geladen sind. */
    void entitiesDurchsehen() {
        Bukkit.getWorlds().forEach(welt -> welt.getEntities().forEach(this::entityNachlesen));
    }

    /** null, wenn der Eintrag kaputt ist. Das Objekt gilt dann als gesperrt. */
    static Set<String> lesen(PersistentDataContainerView pdc) {
        try {
            return Set.copyOf(pdc.getOrDefault(SCHLUESSEL, PersistentDataType.LIST.strings(), List.of()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void schreiben(PersistentDataContainer pdc, Collection<String> kennzeichen) {
        if (kennzeichen.isEmpty()) {
            pdc.remove(SCHLUESSEL);
        } else {
            pdc.set(SCHLUESSEL, PersistentDataType.LIST.strings(), List.copyOf(kennzeichen));
        }
    }

    void welt(Player spieler) {
        welten.put(spieler.getUniqueId(), spieler.getWorld().getUID());
    }

    void entfernen(UUID spieler) {
        welten.remove(spieler);
    }

    void durchsehen(Chunk chunk) {
        var schilder = new HashMap<Vector3i, Set<String>>();
        for (var zustand : chunk.getTileEntities(b -> Tag.ALL_SIGNS.isTagged(b.getType()), false)) {
            var kennzeichen = zustand instanceof Sign schild ? lesen(schild.getPersistentDataContainer()) : null;
            if (kennzeichen != null) {
                schilder.put(new Vector3i(zustand.getX(), zustand.getY(), zustand.getZ()), kennzeichen);
            } else {
                plugin.getLogger().warning("Kennzeichen am Schild bei " + zustand.getX() + " " + zustand.getY() + " "
                        + zustand.getZ() + " nicht lesbar, es bleibt für alle mit Einschränkungen verschleiert.");
            }
        }
        chunks.put(new ChunkOrt(chunk.getWorld().getUID(), chunk.getChunkKey()), Map.copyOf(schilder));
    }

    /** Liest ein einzelnes Schild neu in den Zwischenspeicher (auch: Schild ist weg). */
    void nachlesen(Block block) {
        var welt = block.getWorld();
        if (!welt.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }
        var pos = new Vector3i(block.getX(), block.getY(), block.getZ());
        var kennzeichen = block.getState(false) instanceof Sign schild ? lesen(schild.getPersistentDataContainer()) : null;
        chunks.computeIfPresent(new ChunkOrt(welt.getUID(), Chunk.getChunkKey(pos.x >> 4, pos.z >> 4)), (ort, alt) -> {
            var neu = new HashMap<>(alt);
            if (kennzeichen != null) {
                neu.put(pos, kennzeichen);
            } else {
                neu.remove(pos);
            }
            return Map.copyOf(neu);
        });
    }

    /** Schickt dem Spieler alle bekannten Schilder in den Chunks, die er hat. Der Lauscher verschleiert passend. */
    void nachsenden(Player spieler) {
        var welt = spieler.getWorld();
        chunks.keySet().stream()
                .filter(ort -> ort.welt().equals(welt.getUID()) && spieler.isChunkSent(ort.chunk()))
                .forEach(ort -> nachsenden(spieler, welt, ort));
    }

    // Nur bekannte Schilder: Ein unbekanntes käme verschleiert an, würde neu gemerkt und wieder gesendet, endlos.
    private void nachsenden(Player spieler, World welt, ChunkOrt ort) {
        chunks.getOrDefault(ort, Map.of()).keySet()
                .forEach(pos -> senden(spieler, welt.getBlockAt(pos.x, pos.y, pos.z)));
    }

    /** Schickt das Blockentity (Schild, Regal, …) an den Spieler. Der Lauscher verschleiert passend. */
    void senden(Player spieler, Block block) {
        if (block.getState() instanceof TileState zustand) {
            // Kopie: Auf diesem Weg ließe Paper das PDC (unsere Kennzeichen) im Paket stehen.
            var pdc = zustand.getPersistentDataContainer();
            pdc.getKeys().forEach(pdc::remove);
            spieler.sendBlockUpdate(block.getLocation(), zustand);
        }
    }

    /** Jeden Tick: vom Lauscher gemerkte Chunks durchsehen und ihre Schilder an alle senden, die den Chunk haben. */
    void unbekannteDurchsehen() {
        for (var ort : unbekannt) {
            unbekannt.remove(ort);
            var welt = Bukkit.getWorld(ort.welt());
            int x = (int) ort.chunk();
            int z = (int) (ort.chunk() >>> 32);
            if (welt == null || !welt.isChunkLoaded(x, z)) {
                continue;
            }
            durchsehen(welt.getChunkAt(x, z));
            welt.getPlayers().stream()
                    .filter(spieler -> spieler.isChunkSent(ort.chunk()))
                    .forEach(spieler -> nachsenden(spieler, welt, ort));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beimHinzufuegen(EntityAddToWorldEvent event) {
        entityNachlesen(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beimEntfernen(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            entities.remove(event.getEntity().getEntityId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beimVerlassen(PlayerQuitEvent event) {
        spielerIds.remove(event.getPlayer().getEntityId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beimLaden(ChunkLoadEvent event) {
        durchsehen(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beimEntladen(ChunkUnloadEvent event) {
        chunks.remove(new ChunkOrt(event.getWorld().getUID(), event.getChunk().getChunkKey()));
    }

    // Erst im nächsten Tick: Daten aus dem Item (z. B. Kennzeichen) setzt der Server erst nach dem Event.
    @EventHandler(priority = EventPriority.MONITOR)
    public void beimSetzen(BlockPlaceEvent event) {
        spaeterNachlesen(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beimAbbauen(BlockBreakEvent event) {
        spaeterNachlesen(event.getBlock());
    }

    private void spaeterNachlesen(Block block) {
        if (Tag.ALL_SIGNS.isTagged(block.getType())) {
            Bukkit.getScheduler().runTask(plugin, () -> nachlesen(block));
        }
    }
}
