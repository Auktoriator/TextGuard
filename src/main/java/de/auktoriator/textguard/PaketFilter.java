package de.auktoriator.textguard;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verschleiert Texte in ausgehenden Paketen, je nach Sicht des Empfängers, und setzt in Creative-Paketen die Originale
 * wieder ein. Läuft auf Netty-Threads: kein Welt- oder Bukkit-Zugriff, nur Sichten, Kennzeichen-Zwischenspeicher und
 * Verschleierung.
 */
final class PaketFilter extends PacketListenerAbstract {

    static final byte[] SCHILDTEXT = "front_text".getBytes(StandardCharsets.US_ASCII);
    /** Steckt in written_book_content und writable_book_content. */
    static final byte[] BUCHTEXT = "book_content".getBytes(StandardCharsets.US_ASCII);
    /** Originale pro Spieler. Eine Grenze, weil auch Bücher gemerkt werden, die der Spieler nur einmal gesehen hat. */
    static final int GRENZE = 1024;

    private final Sichten sichten;
    private final Kennzeichen kennzeichen;
    private final Verschleierung verschleierung;
    private final Logger logger;
    private final Map<UUID, Map<String, ItemStack>> originale = new ConcurrentHashMap<>();

    PaketFilter(Sichten sichten, Kennzeichen kennzeichen, Verschleierung verschleierung, Logger logger) {
        this.sichten = sichten;
        this.kennzeichen = kennzeichen;
        this.verschleierung = verschleierung;
        this.logger = logger;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacketType() instanceof PacketType.Play.Server typ)) {
            return;
        }
        switch (typ) {
            case ENTITY_METADATA -> metadaten(event);
            case CHUNK_DATA, BLOCK_ENTITY_DATA -> bloecke(event, typ);
            case WINDOW_ITEMS, SET_SLOT, SET_PLAYER_INVENTORY, SET_CURSOR_ITEM, ENTITY_EQUIPMENT, MERCHANT_OFFERS ->
                    items(event, typ);
            default -> { }
        }
    }

    /**
     * Creative-Clients schicken das ganze Item zurück, also das verschleierte. Statt das Paket zu verwerfen, kommt das
     * Original hinein: Beim Verschieben schickt der Client erst „Feld A leer“ und dann „Feld B = Item“, ein verworfenes
     * B würde das Original löschen.
     */
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            return;
        }
        var uuid = event.getUser().getUUID();
        try {
            var paket = new WrapperPlayClientCreativeInventoryAction(event);
            var gemerkt = uuid == null ? Map.<String, ItemStack>of() : originale.getOrDefault(uuid, Map.of());
            var neu = Verschleierung.wiederherstellen(paket.getItemStack(), gemerkt::get);
            if (neu != paket.getItemStack()) {
                paket.setItemStack(neu);
                event.markForReEncode(true);
            }
        } catch (NoSuchElementException e) {
            // Z. B. eine gespeicherte Creative-Hotbar aus einer früheren Sitzung. Das Original ist davon unberührt.
            event.setCancelled(true);
            logger.warning("Creative-Item von " + event.getUser().getName() + " verworfen: " + e.getMessage());
        } catch (RuntimeException e) {
            // Lieber nichts übernehmen als Runen ins Original schreiben.
            event.setCancelled(true);
            logger.log(Level.WARNING, "Creative-Paket nicht prüfbar, verworfen", e);
        }
    }

    /** Beim Verlassen: Gemerkte Originale braucht nur die laufende Sitzung. */
    void vergessen(UUID spieler) {
        originale.remove(spieler);
    }

    private Map<String, ItemStack> gemerkt(UUID spieler) {
        return originale.computeIfAbsent(spieler, u -> Collections.synchronizedMap(
                new LinkedHashMap<String, ItemStack>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, ItemStack> aeltester) {
                        return size() > GRENZE;
                    }
                }));
    }

    private void items(PacketSendEvent event, PacketType.Play.Server typ) {
        var uuid = event.getUser().getUUID();
        var sicht = uuid == null ? null : sichten.sicht(uuid);
        if (allesLesbar(sicht)) {
            return;
        }
        // Nur Inventar-Items kann ein Creative-Client zurückschicken.
        BiConsumer<String, ItemStack> merken = uuid == null || typ == PacketType.Play.Server.ENTITY_EQUIPMENT
                || typ == PacketType.Play.Server.MERCHANT_OFFERS ? (token, original) -> { } : gemerkt(uuid)::put;
        var geaendert = new boolean[1];
        UnaryOperator<ItemStack> f = alt -> {
            var neu = verschleierung.item(alt, sicht, merken);
            geaendert[0] |= neu != alt;
            return neu;
        };
        try {
            switch (typ) {
                case WINDOW_ITEMS -> {
                    var paket = new WrapperPlayServerWindowItems(event);
                    paket.setItems(new ArrayList<>(paket.getItems().stream().map(f).toList()));
                    paket.getCarriedItem().map(f).ifPresent(paket::setCarriedItem);
                }
                case SET_SLOT -> {
                    var paket = new WrapperPlayServerSetSlot(event);
                    paket.setItem(f.apply(paket.getItem()));
                }
                case SET_PLAYER_INVENTORY -> {
                    var paket = new WrapperPlayServerSetPlayerInventory(event);
                    paket.setStack(f.apply(paket.getStack()));
                }
                case SET_CURSOR_ITEM -> {
                    var paket = new WrapperPlayServerSetCursorItem(event);
                    paket.setStack(f.apply(paket.getStack()));
                }
                case ENTITY_EQUIPMENT -> new WrapperPlayServerEntityEquipment(event).getEquipment()
                        .forEach(e -> e.setItem(f.apply(e.getItem())));
                default -> new WrapperPlayServerMerchantOffers(event).getMerchantOffers()
                        .forEach(o -> o.setOutputItem(f.apply(o.getOutputItem())));
            }
            if (geaendert[0]) {
                event.markForReEncode(true);
            }
        } catch (RuntimeException e) {
            // Ungeprüft weiterschicken könnte Text verraten.
            event.setCancelled(true);
            logger.log(Level.WARNING, "Paket " + typ + " nicht prüfbar, verworfen", e);
        }
    }

    private void bloecke(PacketSendEvent event, PacketType.Play.Server typ) {
        var uuid = event.getUser().getUUID();
        var sicht = uuid == null ? null : sichten.sicht(uuid);
        if (allesLesbar(sicht)) {
            return;
        }
        try {
            if (typ == PacketType.Play.Server.CHUNK_DATA) {
                // Die meisten Chunks haben weder Schild noch Buch und werden so nie dekodiert.
                var puffer = (ByteBuf) event.getByteBuf();
                if (!enthaelt(puffer, SCHILDTEXT) && !enthaelt(puffer, BUCHTEXT)) {
                    return;
                }
                var spalte = new WrapperPlayServerChunkData(event).getColumn();
                for (var be : spalte.getTileEntities()) {
                    if (blockentity(be.getNBT(), uuid, sicht,
                            spalte.getX() * 16 + be.getX(), be.getY(), spalte.getZ() * 16 + be.getZ())) {
                        event.markForReEncode(true);
                    }
                }
            } else {
                var paket = new WrapperPlayServerBlockEntityData(event);
                var pos = paket.getPosition();
                if (blockentity(paket.getNBT(), uuid, sicht, pos.x, pos.y, pos.z)) {
                    event.markForReEncode(true);
                }
            }
        } catch (RuntimeException e) {
            event.setCancelled(true);
            logger.log(Level.WARNING, "Paket " + typ + " nicht prüfbar, verworfen", e);
        }
    }

    private void metadaten(PacketSendEvent event) {
        var uuid = event.getUser().getUUID();
        var sicht = uuid == null ? null : sichten.sicht(uuid);
        var namen = sicht == null || !sicht.allesLesbar(Art.MOBNAMEN);
        var items = !allesLesbar(sicht);
        if (!namen && !items) {
            return;
        }
        try {
            var paket = new WrapperPlayServerEntityMetadata(event);
            var id = paket.getEntityId();
            var geaendert = namen && !kennzeichen.istSpieler(id)
                    && !(sicht != null && kennzeichen.mobnameLesbar(sicht, id))
                    && verschleierung.mobname(paket.getEntityMetadata());
            geaendert |= items && verschleierung.items(paket.getEntityMetadata(), sicht);
            if (geaendert) {
                event.markForReEncode(true);
            }
        } catch (RuntimeException e) {
            event.setCancelled(true);
            logger.log(Level.WARNING, "Entity-Metadaten nicht prüfbar, verworfen", e);
        }
    }

    // Bücher und Schild-Items mit Daten: Dann muss nichts dekodiert werden.
    private static boolean allesLesbar(Sicht sicht) {
        return sicht != null && sicht.allesLesbar(Art.BUECHER) && sicht.allesLesbar(Art.SCHILDER);
    }

    /** Schild selbst (Kennzeichen aus dem Zwischenspeicher) und Items darin (Regal, Lagerfeuer, …). */
    private boolean blockentity(NBTCompound nbt, UUID uuid, Sicht sicht, int x, int y, int z) {
        if (nbt == null) {
            return false;
        }
        var geaendert = (sicht == null || !sicht.allesLesbar(Art.SCHILDER)) && schild(nbt, uuid, sicht, x, y, z);
        return verschleierung.itemsNbt(nbt, sicht) | geaendert;
    }

    /** Verschleiert das Schild, wenn der Empfänger es nicht lesen darf. true, wenn verschleiert wurde. */
    private boolean schild(NBTCompound nbt, UUID uuid, Sicht sicht, int x, int y, int z) {
        if (!Verschleierung.istSchild(nbt)) {
            return false;
        }
        if (sicht != null) {
            var kz = kennzeichen.schild(uuid, x, y, z);
            if (kz != null && sicht.darfLesen(Art.SCHILDER, kz)) {
                return false;
            }
        }
        verschleierung.schild(nbt);
        return true;
    }

    /** Vorfilter auf den rohen Bytes. Findet den Text überall im lesbaren Bereich, ändert den Puffer nicht. */
    static boolean enthaelt(ByteBuf puffer, byte[] text) {
        return ByteBufUtil.indexOf(Unpooled.wrappedBuffer(text), puffer) >= 0;
    }
}
