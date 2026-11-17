package de.auktoriator.textguard;

import static org.junit.jupiter.api.Assertions.*;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.TypedBlockEntityData;
import com.github.retrooper.packetevents.protocol.component.builtin.item.BundleContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemContainerContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WritableBookContent;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WrittenBookContent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.util.Filterable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuecherTest {

    private final Verschleierung v = new Verschleierung(new byte[32]);
    private final Map<String, ItemStack> gemerkt = new HashMap<>();
    private static final Sicht KEINE = sicht(new Regel(false, Set.of()));
    private static final Sicht ALLE_AUSSER_GEHEIM = sicht(new Regel(true, Set.of("geheim")));
    private static final Sicht KEINE_AUSSER_QUEST = sicht(new Regel(false, Set.of("quest")));

    @BeforeAll
    static void packetEvents() {
        PacketEventsAttrappe.einrichten();
    }

    // Item-Verschleierung

    @Test
    void geschriebenesBuchWirdGanzZuRunen() {
        var original = buch();

        var neu = v.item(original, KEINE, gemerkt::put);

        var inhalt = neu.getComponent(ComponentTypes.WRITTEN_BOOK_CONTENT).orElseThrow();
        assertEquals(new Filterable<>(v.runen("Tagebuch"), v.runen("Tagebuch*")), inhalt.getTitle());
        assertEquals(v.runen("Max"), inhalt.getAuthor());
        assertEquals(List.of(new Filterable<>(Component.text(v.runen("Seite eins")), Component.text(v.runen("Seite *"))),
                new Filterable<Component>(Component.text(v.runen("Zwei")))), inhalt.getPages());
        assertEquals(2, inhalt.getGeneration());
        assertTrue(inhalt.isResolved());
        assertEquals(Component.text(v.runen("Mein Buch")), neu.getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow());
        assertEquals(buch(), original, "Original unverändert");
    }

    @Test
    void buchUndFederWirdZuRunen() {
        var original = item(ItemTypes.WRITABLE_BOOK).build();
        original.setComponent(ComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(
                new Filterable<>("Entwurf", "Entw*"), new Filterable<>(""))));

        var neu = v.item(original, KEINE, gemerkt::put);

        assertEquals(List.of(new Filterable<>(v.runen("Entwurf"), v.runen("Entw*")), new Filterable<>("")),
                neu.getComponent(ComponentTypes.WRITABLE_BOOK_CONTENT).orElseThrow().getPages());
        assertEquals(List.of(new Filterable<>("Entwurf", "Entw*"), new Filterable<>("")),
                original.getComponent(ComponentTypes.WRITABLE_BOOK_CONTENT).orElseThrow().getPages());
    }

    @Test
    void verschleiertesItemTraegtTokenUndOriginalWirdGemerkt() {
        var original = buch();

        var neu = v.item(original, KEINE, gemerkt::put);

        var token = token(neu);
        assertEquals(v.token(original), token);
        assertSame(original, gemerkt.get(token));
        assertEquals("bleibt", neu.getComponent(ComponentTypes.CUSTOM_DATA).orElseThrow().getStringTagValueOrNull("eigenes"),
                "vorhandenes custom_data bleibt");
        assertNull(original.getComponent(ComponentTypes.CUSTOM_DATA).orElseThrow().getTagOrNull(Verschleierung.TOKEN));
    }

    @Test
    void lesbaresBuchBleibtUnveraendert() {
        var mitQuest = buch("quest");
        assertSame(mitQuest, v.item(mitQuest, KEINE_AUSSER_QUEST, gemerkt::put));
        var ohne = buch();
        assertSame(ohne, v.item(ohne, ALLE_AUSSER_GEHEIM, gemerkt::put));
        assertTrue(gemerkt.isEmpty());
    }

    @Test
    void kennzeichenEntscheiden() {
        assertNotSame(buch("geheim"), v.item(buch("geheim"), ALLE_AUSSER_GEHEIM, gemerkt::put));
        var ohne = buch();
        assertNotSame(ohne, v.item(ohne, KEINE_AUSSER_QUEST, gemerkt::put));
    }

    @Test
    void ohneSichtOderMitBeschaedigtemKennzeichenGesperrt() {
        var ohne = buch();
        assertNotSame(ohne, v.item(ohne, null, gemerkt::put));
        var kaputt = buch();
        var daten = new NBTCompound();
        var pdc = new NBTCompound();
        pdc.setTag("textguard:kennzeichen", new NBTString("geheim"));
        daten.setTag("PublicBukkitValues", pdc);
        kaputt.setComponent(ComponentTypes.CUSTOM_DATA, daten);
        assertNotSame(kaputt, v.item(kaputt, ALLE_AUSSER_GEHEIM, gemerkt::put));
    }

    @Test
    void leeresBuchUndFederBrauchtKeinToken() {
        var leer = item(ItemTypes.WRITABLE_BOOK).build();
        leer.setComponent(ComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of()));
        assertSame(leer, v.item(leer, KEINE, gemerkt::put));
        var diamant = item(ItemTypes.DIAMOND).build();
        assertSame(diamant, v.item(diamant, KEINE, gemerkt::put));
        assertSame(ItemStack.EMPTY, v.item(ItemStack.EMPTY, KEINE, gemerkt::put));
    }

    @Test
    void buchInShulkerkisteUndBuendel() {
        var kiste = item(ItemTypes.SHULKER_BOX).build();
        kiste.setComponent(ComponentTypes.CONTAINER, new ItemContainerContents(List.of(item(ItemTypes.DIAMOND).build(), buch())));
        var buendel = item(ItemTypes.BUNDLE).build();
        buendel.setComponent(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(List.of(kiste)));

        var neu = v.item(buendel, KEINE, gemerkt::put);

        var neueKiste = neu.getComponent(ComponentTypes.BUNDLE_CONTENTS).orElseThrow().getItems().get(0);
        var inhalt = neueKiste.getComponent(ComponentTypes.CONTAINER).orElseThrow().getItems();
        assertSame(kiste.getComponent(ComponentTypes.CONTAINER).orElseThrow().getItems().get(0), inhalt.get(0),
                "Diamant unverändert");
        assertEquals(v.runen("Max"), inhalt.get(1).getComponent(ComponentTypes.WRITTEN_BOOK_CONTENT).orElseThrow().getAuthor());
        // Jede veränderte Ebene hat ein Token: Bündel, Kiste, Buch.
        assertEquals(Set.of(token(neu), token(neueKiste), token(inhalt.get(1))), gemerkt.keySet());
        assertEquals(buch(), kiste.getComponent(ComponentTypes.CONTAINER).orElseThrow().getItems().get(1),
                "Original im Original unverändert");
        assertTrue(buendel.getComponent(ComponentTypes.CUSTOM_DATA).isEmpty());
    }

    @Test
    void schildTextInItemDaten() {
        var schild = schildItem(Set.of());
        var neu = v.item(schild, KEINE, gemerkt::put);

        var nbt = neu.getComponent(ComponentTypes.TYPED_BLOCK_ENTITY_DATA).orElseThrow().getCompound();
        assertEquals(v.runen("Geheim"), nbt.getCompoundTagOrThrow("front_text").getStringListTagOrThrow("messages")
                .getTag(0).getValue());
        assertEquals("Geheim", schild.getComponent(ComponentTypes.TYPED_BLOCK_ENTITY_DATA).orElseThrow().getCompound()
                .getCompoundTagOrThrow("front_text").getStringListTagOrThrow("messages").getTag(0).getValue());

        var schildSicht = new Sicht(false, Map.of(Art.SCHILDER, List.of(new Regel(false, Set.of("quest")))));
        var mitQuest = schildItem(Set.of("quest"));
        assertSame(mitQuest, v.item(mitQuest, schildSicht, gemerkt::put));
    }

    @Test
    void itemsInEntityMetadaten() {
        var daten = new ArrayList<EntityData<?>>(List.of(
                new EntityData<>(8, null, buch()),
                new EntityData<>(9, null, (byte) 1)));

        assertTrue(v.items(daten, KEINE));

        assertEquals(v.runen("Max"), ((ItemStack) daten.get(0).getValue())
                .getComponent(ComponentTypes.WRITTEN_BOOK_CONTENT).orElseThrow().getAuthor());
        assertEquals((byte) 1, daten.get(1).getValue());
        assertFalse(v.items(new ArrayList<>(List.of(new EntityData<>(8, null, buch()))), ALLE_AUSSER_GEHEIM));
    }

    // Token

    @Test
    void tokenIstStabilUndUnabhaengigVonDerStueckzahl() {
        assertEquals(v.token(buch()), v.token(buch()));
        var stapel = buch();
        stapel.setAmount(16);
        assertEquals(v.token(buch()), v.token(stapel));
        assertNotEquals(v.token(buch()), new Verschleierung(new byte[]{1}).token(buch()), "hängt am Geheimnis");
    }

    @Test
    void tokenAendertSichMitJederKomponente() {
        var varianten = new ArrayList<ItemStack>();
        varianten.add(buch());
        varianten.add(buch("geheim"));
        varianten.add(mitInhalt(new WrittenBookContent(new Filterable<>("Tagebuch!", "Tagebuch*"), "Max", 2, seiten(), true)));
        varianten.add(mitInhalt(new WrittenBookContent(new Filterable<>("Tagebuch", "Tagebuch?"), "Max", 2, seiten(), true)));
        varianten.add(mitInhalt(new WrittenBookContent(new Filterable<>("Tagebuch", "Tagebuch*"), "Moritz", 2, seiten(), true)));
        varianten.add(mitInhalt(new WrittenBookContent(new Filterable<>("Tagebuch", "Tagebuch*"), "Max", 1, seiten(), true)));
        varianten.add(mitInhalt(new WrittenBookContent(new Filterable<>("Tagebuch", "Tagebuch*"), "Max", 2, seiten(), false)));
        varianten.add(mitInhalt(new WrittenBookContent(new Filterable<>("Tagebuch", "Tagebuch*"), "Max", 2,
                List.of(new Filterable<>(Component.text("Seite eins!"), Component.text("Seite *"))), true)));
        var umbenannt = buch();
        umbenannt.setComponent(ComponentTypes.CUSTOM_NAME, Component.text("Anderer Name"));
        varianten.add(umbenannt);
        var glitzernd = buch();
        // Signierte Bücher glitzern ab Werk, false ist also eine Änderung.
        glitzernd.setComponent(ComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
        varianten.add(glitzernd);
        var teuer = buch();
        teuer.setComponent(ComponentTypes.REPAIR_COST, 5);
        varianten.add(teuer);
        var ohneName = buch();
        ohneName.unsetComponent(ComponentTypes.CUSTOM_NAME);
        varianten.add(ohneName);
        var anderesItem = buch();
        varianten.add(ItemStack.builder().type(ItemTypes.WRITABLE_BOOK).amount(1).components(anderesItem.getComponents()).build());

        var tokens = new HashSet<String>();
        varianten.forEach(i -> tokens.add(v.token(i)));
        assertEquals(varianten.size(), tokens.size());
    }

    // Creative

    @Test
    void creativeSetztDasOriginalMitStueckzahlDesClientsEin() {
        var original = buch();
        var vomClient = v.item(original, KEINE, gemerkt::put).copy();
        vomClient.setAmount(3);

        var zurueck = Verschleierung.wiederherstellen(vomClient, gemerkt::get);

        assertEquals(3, zurueck.getAmount());
        zurueck.setAmount(1);
        assertEquals(buch(), zurueck);
    }

    @Test
    void creativeStelltVerschachtelteItemsWiederHer() {
        var buendel = item(ItemTypes.BUNDLE).build();
        buendel.setComponent(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(List.of(buch())));
        var gesendet = v.item(buendel, KEINE, gemerkt::put);

        assertEquals(buendel, Verschleierung.wiederherstellen(gesendet, gemerkt::get));

        // Ein neues Bündel ohne Token, in das der Client das verschleierte Buch gesteckt hat.
        var neuesBuendel = item(ItemTypes.BUNDLE).build();
        neuesBuendel.setComponent(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(List.of(
                gesendet.getComponent(ComponentTypes.BUNDLE_CONTENTS).orElseThrow().getItems().get(0))));
        assertEquals(buendel, Verschleierung.wiederherstellen(neuesBuendel, gemerkt::get));
    }

    @Test
    void creativeBehaeltUmgepacktesBuendel() {
        var buendel = item(ItemTypes.BUNDLE).build();
        buendel.setComponent(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(List.of(buch())));
        var gesendet = v.item(buendel, KEINE, gemerkt::put);
        var diamant = item(ItemTypes.DIAMOND).build();
        // Der Client hat das Buch herausgenommen und einen Diamanten hineingesteckt.
        var vomClient = gesendet.copy();
        vomClient.setComponent(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(List.of(diamant)));

        var zurueck = Verschleierung.wiederherstellen(vomClient, gemerkt::get);

        assertEquals(List.of(diamant), zurueck.getComponent(ComponentTypes.BUNDLE_CONTENTS).orElseThrow().getItems());
        assertTrue(zurueck.getComponent(ComponentTypes.CUSTOM_DATA).isEmpty(), "Token ist weg");
    }

    @Test
    void creativeMitUnbekanntemTokenWirdVerworfen() {
        var gesendet = v.item(buch(), KEINE, gemerkt::put);
        assertThrows(NoSuchElementException.class, () -> Verschleierung.wiederherstellen(gesendet, Map.<String, ItemStack>of()::get));

        var buendel = item(ItemTypes.BUNDLE).build();
        buendel.setComponent(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(List.of(gesendet)));
        assertThrows(NoSuchElementException.class, () -> Verschleierung.wiederherstellen(buendel, Map.<String, ItemStack>of()::get),
                "auch verschachtelt");
    }

    @Test
    void creativeOhneTokenBleibtUnveraendert() {
        var buch = buch();
        assertSame(buch, Verschleierung.wiederherstellen(buch, gemerkt::get));
        assertSame(ItemStack.EMPTY, Verschleierung.wiederherstellen(ItemStack.EMPTY, gemerkt::get));
    }

    // NBT in Blockentities

    @Test
    void buecherImRegalWerdenZuRunen() {
        var regal = regal(buchNbt(null));
        var vorher = regal.copy();

        assertTrue(v.itemsNbt(regal, KEINE));

        var komponenten = regal.getCompoundListTagOrThrow("Items").getTag(0).getCompoundTagOrThrow("components");
        var inhalt = komponenten.getCompoundTagOrThrow("minecraft:written_book_content");
        var titel = inhalt.getCompoundTagOrThrow("title");
        assertEquals(v.runen("Tagebuch"), titel.getStringTagValueOrThrow("raw"));
        assertEquals(v.runen("Tagebuch*"), titel.getStringTagValueOrThrow("filtered"));
        assertEquals(v.runen("Max"), inhalt.getStringTagValueOrThrow("author"));
        assertEquals(new NBTInt(2), inhalt.getTagOrNull("generation"));
        var seiten = inhalt.getTagListOfTypeOrThrow("pages", NBTCompound.class).getTags();
        assertEquals(v.runen("Seite eins"), seiten.get(0).getStringTagValueOrThrow("raw"));
        assertEquals(v.runen("Seite *"), seiten.get(0).getStringTagValueOrThrow("filtered"));
        assertEquals(v.runen("Zwei"), seiten.get(1).getStringTagValueOrThrow(""), "eingepackter String");
        assertEquals(v.runen("Rot"), seiten.get(2).getStringTagValueOrThrow(""), "Textkomponente");
        assertEquals(v.runen("Mein Buch"), komponenten.getStringTagValueOrThrow("minecraft:custom_name"));
        assertNotEquals(vorher, regal);
    }

    @Test
    void lesbareBuecherImRegalBleiben() {
        var regal = regal(buchNbt("quest"));
        var vorher = regal.copy();
        assertFalse(v.itemsNbt(regal, KEINE_AUSSER_QUEST));
        assertEquals(vorher, regal);
    }

    @Test
    void buchUndFederUndVerschachtelteBuecherImNbt() {
        var feder = new NBTCompound();
        var seiten = new NBTList<>(NBTType.STRING);
        seiten.addTag(new NBTString("Notiz"));
        var inhalt = new NBTCompound();
        inhalt.setTag("pages", seiten);
        var federKomponenten = new NBTCompound();
        federKomponenten.setTag("minecraft:writable_book_content", inhalt);
        feder.setTag("id", new NBTString("minecraft:writable_book"));
        feder.setTag("components", federKomponenten);
        // Shulkerkiste mit dem Buch und Feder darin, im Regal.
        var eintrag = new NBTCompound();
        eintrag.setTag("slot", new NBTInt(0));
        eintrag.setTag("item", feder);
        var kisteInhalt = NBTList.createCompoundList();
        kisteInhalt.addTag(eintrag);
        var kisteKomponenten = new NBTCompound();
        kisteKomponenten.setTag("minecraft:container", kisteInhalt);
        var kiste = new NBTCompound();
        kiste.setTag("id", new NBTString("minecraft:shulker_box"));
        kiste.setTag("components", kisteKomponenten);
        var regal = regal(kiste);

        assertTrue(v.itemsNbt(regal, KEINE));

        assertEquals(v.runen("Notiz"), inhalt.getStringListTagOrThrow("pages").getTag(0).getValue());
    }

    @Test
    void schildItemImNbtAberNichtDasSchildSelbst() {
        var schildDaten = schildNbt(Set.of());
        var komponenten = new NBTCompound();
        komponenten.setTag("minecraft:block_entity_data", schildDaten);
        var schildItem = new NBTCompound();
        schildItem.setTag("id", new NBTString("minecraft:oak_sign"));
        schildItem.setTag("components", komponenten);
        var regal = regal(schildItem);

        assertTrue(v.itemsNbt(regal, KEINE));
        assertEquals(v.runen("Geheim"), schildDaten.getCompoundTagOrThrow("front_text")
                .getStringListTagOrThrow("messages").getTag(0).getValue());

        // Das Schild selbst entscheidet der Paketlauscher über den Zwischenspeicher.
        var schild = schildNbt(Set.of());
        assertFalse(v.itemsNbt(schild, KEINE));
    }

    // Kennzeichen im NBT

    @Test
    void kennzeichenAusDemPdc() {
        assertEquals(Set.of(), Verschleierung.kennzeichen(null));
        assertEquals(Set.of(), Verschleierung.kennzeichen(new NBTCompound()));
        assertEquals(Set.of("a", "b"), Verschleierung.kennzeichen(pdc(Set.of("a", "b"))));
        assertNull(Verschleierung.kennzeichen(new NBTString("{}")), "custom_data als String");
        var falsch = new NBTCompound();
        falsch.setTag("PublicBukkitValues", new NBTString("x"));
        assertNull(Verschleierung.kennzeichen(falsch));
    }

    // Hilfen

    private static Sicht sicht(Regel regel) {
        return new Sicht(false, Map.of(Art.BUECHER, List.of(regel)));
    }

    private static String token(ItemStack item) {
        return item.getComponent(ComponentTypes.CUSTOM_DATA).orElseThrow().getStringTagValueOrThrow(Verschleierung.TOKEN);
    }

    private static ItemStack.Builder item(ItemType typ) {
        return ItemStack.builder().type(typ).amount(1);
    }

    private static List<Filterable<Component>> seiten() {
        return List.of(new Filterable<>(Component.text("Seite eins"), Component.text("Seite *")),
                new Filterable<Component>(Component.text("Zwei")));
    }

    private static ItemStack mitInhalt(WrittenBookContent inhalt) {
        var buch = buch();
        buch.setComponent(ComponentTypes.WRITTEN_BOOK_CONTENT, inhalt);
        return buch;
    }

    /** Immer ein neues, gleiches Buch: zum Vergleich, ob das Original unverändert blieb. */
    private static ItemStack buch(String... kennzeichen) {
        var daten = pdc(Set.of(kennzeichen));
        daten.setTag("eigenes", new NBTString("bleibt"));
        return item(ItemTypes.WRITTEN_BOOK)
                .component(ComponentTypes.WRITTEN_BOOK_CONTENT,
                        new WrittenBookContent(new Filterable<>("Tagebuch", "Tagebuch*"), "Max", 2, seiten(), true))
                .component(ComponentTypes.CUSTOM_NAME, Component.text("Mein Buch"))
                .component(ComponentTypes.CUSTOM_DATA, daten)
                .build();
    }

    private static NBTCompound pdc(Set<String> kennzeichen) {
        var daten = new NBTCompound();
        if (!kennzeichen.isEmpty()) {
            var liste = new NBTList<>(NBTType.STRING);
            kennzeichen.forEach(k -> liste.addTag(new NBTString(k)));
            var pdc = new NBTCompound();
            pdc.setTag("textguard:kennzeichen", liste);
            daten.setTag("PublicBukkitValues", pdc);
        }
        return daten;
    }

    private static NBTCompound schildNbt(Set<String> kennzeichen) {
        var zeilen = new NBTList<>(NBTType.STRING);
        List.of("Geheim", "", "", "").forEach(z -> zeilen.addTag(new NBTString(z)));
        var vorne = new NBTCompound();
        vorne.setTag("messages", zeilen);
        var schild = pdc(kennzeichen);
        schild.setTag("front_text", vorne);
        return schild;
    }

    private static ItemStack schildItem(Set<String> kennzeichen) {
        return item(ItemTypes.OAK_SIGN).component(ComponentTypes.TYPED_BLOCK_ENTITY_DATA,
                new TypedBlockEntityData(BlockEntityTypes.SIGN, schildNbt(kennzeichen))).build();
    }

    /** Ein signiertes Buch, wie es im Regal-NBT steht: Titel gefiltert, Seiten gemischt. */
    private static NBTCompound buchNbt(String kennzeichen) {
        var titel = new NBTCompound();
        titel.setTag("raw", new NBTString("Tagebuch"));
        titel.setTag("filtered", new NBTString("Tagebuch*"));
        var gefiltert = new NBTCompound();
        gefiltert.setTag("raw", new NBTString("Seite eins"));
        gefiltert.setTag("filtered", new NBTString("Seite *"));
        var rot = new NBTCompound();
        rot.setTag("text", new NBTString("Rot"));
        rot.setTag("color", new NBTString("red"));
        var seiten = NBTList.createCompoundList();
        seiten.addTag(gefiltert);
        ((NBTList<?>) seiten).addTagOrWrap(new NBTString("Zwei"));
        seiten.addTag(rot);
        var inhalt = new NBTCompound();
        inhalt.setTag("title", titel);
        inhalt.setTag("author", new NBTString("Max"));
        inhalt.setTag("generation", new NBTInt(2));
        inhalt.setTag("pages", seiten);
        var komponenten = new NBTCompound();
        komponenten.setTag("minecraft:written_book_content", inhalt);
        komponenten.setTag("minecraft:custom_name", new NBTString("Mein Buch"));
        komponenten.setTag("minecraft:custom_data", pdc(kennzeichen == null ? Set.of() : Set.of(kennzeichen)));
        var buch = new NBTCompound();
        buch.setTag("id", new NBTString("minecraft:written_book"));
        buch.setTag("count", new NBTInt(1));
        buch.setTag("components", komponenten);
        return buch;
    }

    private static NBTCompound regal(NBTCompound item) {
        item.setTag("Slot", new NBTInt(0));
        var items = NBTList.createCompoundList();
        items.addTag(item);
        var regal = new NBTCompound();
        regal.setTag("Items", items);
        regal.setTag("align_items_to_bottom", new NBTInt(0));
        return regal;
    }
}
