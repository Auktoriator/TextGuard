package de.auktoriator.textguard;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verschleiert Texte in ausgehenden Paketen, je nach Sicht des Empfängers. Läuft auf Netty-Threads: kein Welt- oder
 * Bukkit-Zugriff, nur Sichten, Kennzeichen-Zwischenspeicher und Verschleierung.
 */
final class PaketFilter extends PacketListenerAbstract {

    static final byte[] SCHILDTEXT = "front_text".getBytes(StandardCharsets.US_ASCII);

    private final Sichten sichten;
    private final Kennzeichen kennzeichen;
    private final Verschleierung verschleierung;
    private final Logger logger;

    PaketFilter(Sichten sichten, Kennzeichen kennzeichen, Verschleierung verschleierung, Logger logger) {
        this.sichten = sichten;
        this.kennzeichen = kennzeichen;
        this.verschleierung = verschleierung;
        this.logger = logger;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        var typ = event.getPacketType();
        if (typ == PacketType.Play.Server.ENTITY_METADATA) {
            mobname(event);
            return;
        }
        if (typ != PacketType.Play.Server.CHUNK_DATA && typ != PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            return;
        }
        var uuid = event.getUser().getUUID();
        var sicht = uuid == null ? null : sichten.sicht(uuid);
        if (sicht != null && sicht.allesLesbar(Art.SCHILDER)) {
            return;
        }
        try {
            if (typ == PacketType.Play.Server.CHUNK_DATA) {
                // Die meisten Chunks haben kein Schild und werden so nie dekodiert.
                if (!enthaelt((ByteBuf) event.getByteBuf(), SCHILDTEXT)) {
                    return;
                }
                var spalte = new WrapperPlayServerChunkData(event).getColumn();
                for (var be : spalte.getTileEntities()) {
                    if (schild(be.getNBT(), uuid, sicht,
                            spalte.getX() * 16 + be.getX(), be.getY(), spalte.getZ() * 16 + be.getZ())) {
                        event.markForReEncode(true);
                    }
                }
            } else {
                var paket = new WrapperPlayServerBlockEntityData(event);
                var pos = paket.getPosition();
                if (schild(paket.getNBT(), uuid, sicht, pos.x, pos.y, pos.z)) {
                    event.markForReEncode(true);
                }
            }
        } catch (RuntimeException e) {
            // Ungeprüft weiterschicken könnte Text verraten.
            event.setCancelled(true);
            logger.log(Level.WARNING, "Paket " + typ + " nicht prüfbar, verworfen", e);
        }
    }

    private void mobname(PacketSendEvent event) {
        var uuid = event.getUser().getUUID();
        var sicht = uuid == null ? null : sichten.sicht(uuid);
        if (sicht != null && sicht.allesLesbar(Art.MOBNAMEN)) {
            return;
        }
        try {
            var paket = new WrapperPlayServerEntityMetadata(event);
            if (kennzeichen.istSpieler(paket.getEntityId())
                    || sicht != null && kennzeichen.mobnameLesbar(sicht, paket.getEntityId())) {
                return;
            }
            if (verschleierung.mobname(paket.getEntityMetadata())) {
                event.markForReEncode(true);
            }
        } catch (RuntimeException e) {
            event.setCancelled(true);
            logger.log(Level.WARNING, "Entity-Metadaten nicht prüfbar, verworfen", e);
        }
    }

    /** Verschleiert das Schild, wenn der Empfänger es nicht lesen darf. true, wenn verschleiert wurde. */
    private boolean schild(NBTCompound nbt, UUID uuid, Sicht sicht, int x, int y, int z) {
        if (nbt == null || !Verschleierung.istSchild(nbt)) {
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
