package de.auktoriator.textguard;

import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Objekt, dessen Kennzeichen ein Befehl oder das Werkzeug liest oder ändert. schreiben speichert neue Kennzeichen und
 * sendet nach. Nur im Hauptthread.
 */
record Ziel(Art art, String name, PersistentDataContainerView pdc, Consumer<List<String>> schreiben) {

    static Ziel entity(Entity entity, Kennzeichen kennzeichen) {
        var name = entity.customName();
        var beschreibung = entity.getType().key().value() + (name == null ? ""
                : " „" + PlainTextComponentSerializer.plainText().serialize(name) + "“");
        return new Ziel(Art.MOBNAMEN, beschreibung, entity.getPersistentDataContainer(), kz -> {
            Kennzeichen.schreiben(entity.getPersistentDataContainer(), kz);
            kennzeichen.entityNachlesen(entity);
            kennzeichen.nameSenden(entity);
        });
    }

    /** null, wenn dort kein Schild steht. */
    static Ziel schild(Block block, Kennzeichen kennzeichen) {
        if (!(block.getState() instanceof Sign schild)) {
            return null;
        }
        // update() schreibt die Kopie zurück und schickt das Schild am Tickende an alle, die den Chunk haben.
        // Der Zwischenspeicher muss vorher stimmen.
        var pdc = schild.getPersistentDataContainer();
        return new Ziel(Art.SCHILDER, "Schild bei " + ort(block), pdc, kz -> {
            Kennzeichen.schreiben(pdc, kz);
            schild.update();
            kennzeichen.nachlesen(block);
        });
    }

    /** Signiertes Buch oder Buch und Feder in der Haupthand, sonst null. */
    static Ziel buch(Player spieler) {
        // Ein Spiegel: Änderungen landen direkt im Inventar.
        var item = spieler.getInventory().getItemInMainHand();
        if (item.getType() != Material.WRITTEN_BOOK && item.getType() != Material.WRITABLE_BOOK) {
            return null;
        }
        var name = "Buch „" + PlainTextComponentSerializer.plainText().serialize(item.effectiveName()) + "“ in der Hand";
        // Direkt ins custom_data statt über editMeta: BookMeta kennt keine gefilterten Seiten und würde sie verlieren.
        return new Ziel(Art.BUECHER, name, item.getPersistentDataContainer(), kz -> {
            item.editPersistentDataContainer(pdc -> Kennzeichen.schreiben(pdc, kz));
            spieler.updateInventory();
        });
    }

    static String ort(Block block) {
        return block.getX() + " " + block.getY() + " " + block.getZ() + " (" + block.getWorld().getName() + ")";
    }

    /** Ändert ein Kennzeichen. Gibt den Grund zurück, wenn nichts geändert wurde, sonst null. */
    String aendern(String kennzeichen, boolean hinzufuegen) {
        var vorher = Kennzeichen.lesen(pdc);
        if (vorher == null) {
            return name + ": Die gespeicherten Kennzeichen sind beschädigt, nichts geändert.";
        }
        if (vorher.contains(kennzeichen) == hinzufuegen) {
            return name + " hat „" + kennzeichen + "“ " + (hinzufuegen ? "schon." : "nicht.");
        }
        var neu = new TreeSet<>(vorher);
        if (hinzufuegen) {
            neu.add(kennzeichen);
        } else {
            neu.remove(kennzeichen);
        }
        schreiben.accept(new ArrayList<>(neu));
        return null;
    }

    /** Gruppen, die das Objekt lesen dürfen, mit Komma getrennt, oder „keine“. null, wenn die Kennzeichen beschädigt sind. */
    String lesbarFuer(Regelwerk regelwerk) {
        var kz = Kennzeichen.lesen(pdc);
        if (kz == null) {
            return null;
        }
        var lesbar = regelwerk.gruppen().values().stream()
                .filter(g -> g.regel(art).erlaubt(kz)).map(Gruppe::name).collect(Collectors.joining(", "));
        return lesbar.isEmpty() ? "keine" : lesbar;
    }
}
