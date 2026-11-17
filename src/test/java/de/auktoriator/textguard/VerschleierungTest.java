package de.auktoriator.textguard;

import static org.junit.jupiter.api.Assertions.*;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;

class VerschleierungTest {

    private final Verschleierung v = new Verschleierung(new byte[32]);

    @Test
    void runenHabenGleichVieleZeichen() {
        for (var text : List.of("a", "Hallo Welt", "  ", "Grüße 😀!")) {
            var runen = v.runen(text);
            assertEquals(text.codePointCount(0, text.length()), runen.codePointCount(0, runen.length()), text);
        }
    }

    @Test
    void runenSindStabilUndHaengenAmGeheimnis() {
        assertEquals(v.runen("geheime Botschaft"), v.runen("geheime Botschaft"));
        var anderes = new byte[32];
        anderes[0] = 1;
        assertNotEquals(v.runen("geheime Botschaft"), new Verschleierung(anderes).runen("geheime Botschaft"));
    }

    @Test
    void nurRunenzeichen() {
        var runen = v.runen("Jedes Zeichen, auch Leerzeichen und 123!");
        assertTrue(runen.codePoints().allMatch(c -> c >= 0x16A0 && c <= 0x16EA), runen);
    }

    @Test
    void leerBleibtLeer() {
        assertEquals("", v.runen(""));
    }

    @Test
    void textSammeltAlleInhalte() {
        var verschachtelt = new NBTCompound();
        verschachtelt.setTag("text", new NBTString("ab"));
        verschachtelt.setTag("color", new NBTString("red"));
        var extra = NBTList.createCompoundList();
        var eingepackt = new NBTCompound();
        eingepackt.setTag("", new NBTString("c"));
        extra.addTag(eingepackt);
        var uebersetzt = new NBTCompound();
        uebersetzt.setTag("translate", new NBTString("d"));
        extra.addTag(uebersetzt);
        verschachtelt.setTag("extra", extra);
        assertEquals("abcd", Verschleierung.text(verschachtelt));
    }

    @Test
    void schildVerschleiertAlleZeilenBeiderSeiten() {
        var nbt = new NBTCompound();
        nbt.setTag("front_text", seite(List.of(s("Hallo"), farbig("Welt"), s(""), s("x")),
                List.of(s("Gefiltert"), s(""), s(""), s(""))));
        nbt.setTag("back_text", seite(List.of(s(""), s("Rückseite"), s(""), s("")), null));
        nbt.setTag("is_waxed", new NBTByte((byte) 1));
        var vorher = nbt.copy();

        v.schild(nbt);

        var vorne = nbt.getCompoundTagOrThrow("front_text");
        assertEquals(List.of(v.runen("Hallo"), v.runen("Welt"), "", v.runen("x")), zeilen(vorne, "messages"));
        assertEquals(List.of(v.runen("Gefiltert"), "", "", ""), zeilen(vorne, "filtered_messages"));
        var hinten = nbt.getCompoundTagOrThrow("back_text");
        assertEquals(List.of("", v.runen("Rückseite"), "", ""), zeilen(hinten, "messages"));
        assertNull(hinten.getTagOrNull("filtered_messages"));
        // Alles außer den Zeilen bleibt.
        assertEquals(vorher.getTagOrNull("is_waxed"), nbt.getTagOrNull("is_waxed"));
        for (var seite : List.of("front_text", "back_text")) {
            var alt = vorher.getCompoundTagOrThrow(seite);
            var neu = nbt.getCompoundTagOrThrow(seite);
            assertEquals(alt.getTagOrNull("color"), neu.getTagOrNull("color"));
            assertEquals(alt.getTagOrNull("has_glowing_text"), neu.getTagOrNull("has_glowing_text"));
        }
    }

    // Der Typ bleibt null: Die Typ-Tabelle von PacketEvents braucht eine laufende PacketEvents-Instanz.
    @Test
    void mobnameWirdZuRunenGleicherLaenge() {
        var daten = new ArrayList<EntityData<?>>(List.of(
                new EntityData<>(0, null, (byte) 1),
                new EntityData<>(2, null, Optional.of(Component.text("Bert der Geheime"))),
                new EntityData<>(3, null, true)));

        assertTrue(v.mobname(daten));

        assertEquals(Optional.of(Component.text(v.runen("Bert der Geheime"))), daten.get(1).getValue());
        assertEquals((byte) 1, daten.get(0).getValue());
        assertEquals(true, daten.get(2).getValue());
    }

    @Test
    void mobnameAlsJsonBleibtJson() {
        var json = GsonComponentSerializer.gson().serialize(Component.text("Bert"));
        var daten = new ArrayList<EntityData<?>>(List.of(
                new EntityData<>(2, null, Optional.of(json))));

        assertTrue(v.mobname(daten));

        assertEquals(Optional.of(GsonComponentSerializer.gson().serialize(Component.text(v.runen("Bert")))),
                daten.get(0).getValue());
    }

    @Test
    void mobnameLeerOderFehlendBleibt() {
        var leer = new ArrayList<EntityData<?>>(List.of(
                new EntityData<>(2, null, Optional.empty())));
        assertFalse(v.mobname(leer));
        assertEquals(Optional.empty(), leer.get(0).getValue());

        var leererText = new ArrayList<EntityData<?>>(List.of(
                new EntityData<>(2, null, Optional.of(Component.empty()))));
        assertFalse(v.mobname(leererText));

        assertFalse(v.mobname(new ArrayList<>(List.of(new EntityData<>(0, null, (byte) 1)))));
    }

    @Test
    void erkenntSchild() {
        var schild = new NBTCompound();
        schild.setTag("back_text", new NBTCompound());
        assertTrue(Verschleierung.istSchild(schild));
        assertFalse(Verschleierung.istSchild(new NBTCompound()));
    }

    private static NBTCompound seite(List<NBT> messages, List<NBT> gefiltert) {
        var seite = new NBTCompound();
        seite.setTag("messages", liste(messages));
        if (gefiltert != null) {
            seite.setTag("filtered_messages", liste(gefiltert));
        }
        seite.setTag("color", new NBTString("blue"));
        seite.setTag("has_glowing_text", new NBTByte((byte) 1));
        return seite;
    }

    /** Wie im Spiel: reine String-Liste, gemischt eine Compound-Liste mit eingepackten Strings. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NBTList<NBT> liste(List<NBT> zeilen) {
        var gemischt = zeilen.stream().anyMatch(z -> !(z instanceof NBTString));
        NBTList<NBT> liste = new NBTList(gemischt ? NBTType.COMPOUND : NBTType.STRING);
        zeilen.forEach(liste::addTagOrWrap);
        return liste;
    }

    private static NBTString s(String text) {
        return new NBTString(text);
    }

    private static NBTCompound farbig(String text) {
        var c = new NBTCompound();
        c.setTag("text", new NBTString(text));
        c.setTag("color", new NBTString("red"));
        return c;
    }

    private static List<String> zeilen(NBTCompound seite, String schluessel) {
        return seite.getStringListTagOrThrow(schluessel).getTags().stream().map(NBTString::getValue).toList();
    }
}
