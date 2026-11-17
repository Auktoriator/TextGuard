package de.auktoriator.textguard;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.TypedBlockEntityData;
import com.github.retrooper.packetevents.protocol.component.builtin.item.BundleContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ChargedProjectiles;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemContainerContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WritableBookContent;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WrittenBookContent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.util.Filterable;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Macht Texte unlesbar. Rein (kein Bukkit, nur das Server-Geheimnis und für Tokens die Item-Kodierung von PacketEvents),
 * deshalb auch in Netty-Threads nutzbar.
 */
public final class Verschleierung {

    private static final int ERSTE_RUNE = 0x16A0;
    private static final int RUNEN = 0x16EA - ERSTE_RUNE + 1;
    private static final int INDEX_MOBNAME = 2;
    private static final List<String> SEITEN = List.of("front_text", "back_text");
    private static final List<String> ZEILEN = List.of("messages", "filtered_messages");
    // "" ist der Schlüssel, unter dem gemischte NBT-Listen einzelne Strings einpacken.
    private static final Set<String> TEXTSCHLUESSEL = Set.of("text", "translate", "fallback", "keybind", "");

    /** Schlüssel im custom_data eines verschleierten Items, unter dem das Original gemerkt ist. */
    public static final String TOKEN = "textguard:token";
    private static final String GESCHRIEBEN = "minecraft:written_book_content";
    private static final String BESCHREIBBAR = "minecraft:writable_book_content";
    /** Was die Item-Verschleierung auf einer Ebene selbst ändert. Verschachtelte Items haben eigene Tokens. */
    private static final List<ComponentType<?>> EIGENE = List.of(ComponentTypes.WRITTEN_BOOK_CONTENT,
            ComponentTypes.WRITABLE_BOOK_CONTENT, ComponentTypes.CUSTOM_NAME, ComponentTypes.CUSTOM_DATA,
            ComponentTypes.TYPED_BLOCK_ENTITY_DATA);

    private final SecretKeySpec geheimnis;

    public Verschleierung(byte[] geheimnis) {
        this.geheimnis = new SecretKeySpec(geheimnis, "HmacSHA256");
    }

    /**
     * Gleich viele Runen wie der Text Zeichen hat. Der Zufall hängt am ganzen Text und am Geheimnis: stabil, aber ohne
     * Geheimnis nicht durch Ausprobieren zurückzurechnen.
     */
    public String runen(String text) {
        if (text.isEmpty()) {
            return text;
        }
        var zufall = new SplittableRandom(ByteBuffer.wrap(hmac(text.getBytes(StandardCharsets.UTF_8))).getLong());
        var runen = new StringBuilder();
        text.codePoints().forEach(c -> runen.appendCodePoint(ERSTE_RUNE + zufall.nextInt(RUNEN)));
        return runen.toString();
    }

    private Component runen(Component text) {
        return Component.text(runen(PlainTextComponentSerializer.plainText().serialize(text)));
    }

    private byte[] hmac(byte[]... teile) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(geheimnis);
            for (var teil : teile) {
                mac.update(teil);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 fehlt", e);
        }
    }

    /**
     * Stabil für dasselbe Original, verschieden für verschiedene: HMAC über die Netzwerk-Kodierung des Items (Typ und
     * alle Komponenten), mit Stückzahl 1, damit Stapelgrößen dasselbe Token teilen.
     */
    String token(ItemStack original) {
        var eins = original.copy();
        eins.setAmount(1);
        var puffer = Unpooled.buffer();
        PacketWrapper.createUniversalPacketWrapper(puffer, PacketEvents.getAPI().getServerManager().getVersion())
                .writeItemStack(eins);
        return HexFormat.of().formatHex(hmac(TOKEN.getBytes(StandardCharsets.UTF_8), ByteBufUtil.getBytes(puffer)), 0, 16);
    }

    /**
     * Item aus einem Paket. Ohne etwas Gesperrtes kommt das Item selbst zurück, sonst eine Kopie mit Runen und Token im
     * custom_data, auf jeder veränderten Ebene (auch verschachtelt). Das Original wird nie verändert und geht mit seinem
     * Token an merken. sicht null: alles gesperrt.
     */
    public ItemStack item(ItemStack item, Sicht sicht, BiConsumer<String, ItemStack> merken) {
        if (item == null || item.isEmpty()) {
            return item;
        }
        var neu = item.copy();
        UnaryOperator<ItemStack> innen = i -> item(i, sicht, merken);
        if (!(buch(neu, sicht) | blockdaten(neu, sicht) | verschachtelt(neu, innen))) {
            return item;
        }
        var token = token(item);
        var daten = item.getComponent(ComponentTypes.CUSTOM_DATA).map(NBTCompound::copy).orElseGet(NBTCompound::new);
        daten.setTag(TOKEN, new NBTString(token));
        neu.setComponent(ComponentTypes.CUSTOM_DATA, daten);
        merken.accept(token, item);
        return neu;
    }

    // Die Komponenten-Werte teilt die Kopie mit dem Original, deshalb immer neue Werte setzen statt sie zu ändern.
    private boolean buch(ItemStack neu, Sicht sicht) {
        var geschrieben = neu.getComponent(ComponentTypes.WRITTEN_BOOK_CONTENT);
        var beschreibbar = neu.getComponent(ComponentTypes.WRITABLE_BOOK_CONTENT);
        if (geschrieben.isEmpty() && beschreibbar.isEmpty() || lesbar(sicht, Art.BUECHER,
                kennzeichen(neu.getComponent(ComponentTypes.CUSTOM_DATA).orElse(null)))) {
            return false;
        }
        var vorher = neu.getComponents().copy();
        geschrieben.ifPresent(b -> neu.setComponent(ComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                filterbar(b.getTitle(), this::runen), runen(b.getAuthor()), b.getGeneration(),
                b.getPages().stream().map(s -> filterbar(s, this::runen)).toList(), b.isResolved())));
        beschreibbar.ifPresent(b -> neu.setComponent(ComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContent(
                b.getPages().stream().map(s -> filterbar(s, this::runen)).toList())));
        neu.getComponent(ComponentTypes.CUSTOM_NAME).ifPresent(n -> neu.setComponent(ComponentTypes.CUSTOM_NAME, runen(n)));
        // Ein leeres Buch und Feder bleibt so, wie es ist, und braucht kein Token.
        return !neu.getComponents().equals(vorher);
    }

    private static <T> Filterable<T> filterbar(Filterable<T> wert, UnaryOperator<T> f) {
        return new Filterable<>(f.apply(wert.getRaw()), Optional.ofNullable(wert.getFiltered()).map(f));
    }

    /** block_entity_data an Items, z. B. ein Schild per Creative-Pick mit Daten. */
    private boolean blockdaten(ItemStack neu, Sicht sicht) {
        var daten = neu.getComponent(ComponentTypes.TYPED_BLOCK_ENTITY_DATA);
        if (daten.isEmpty()) {
            return false;
        }
        var nbt = daten.get().getCompound().copy();
        if (!(schildNbt(nbt, sicht) | itemsNbt(nbt, sicht))) {
            return false;
        }
        neu.setComponent(ComponentTypes.TYPED_BLOCK_ENTITY_DATA, new TypedBlockEntityData(daten.get().getType(), nbt));
        return true;
    }

    /** Shulker-Inhalt, Bündel und geladene Pfeile; f bekommt jedes Item darin. true, wenn f etwas ersetzt hat. */
    private static boolean verschachtelt(ItemStack neu, UnaryOperator<ItemStack> f) {
        return verschachtelt(neu, ComponentTypes.CONTAINER, ItemContainerContents::getItems, ItemContainerContents::new, f)
                | verschachtelt(neu, ComponentTypes.BUNDLE_CONTENTS, BundleContents::getItems, BundleContents::new, f)
                | verschachtelt(neu, ComponentTypes.CHARGED_PROJECTILES, ChargedProjectiles::getItems,
                        ChargedProjectiles::new, f);
    }

    private static <T> boolean verschachtelt(ItemStack neu, ComponentType<T> typ, Function<T, List<ItemStack>> lesen,
            Function<List<ItemStack>, T> bauen, UnaryOperator<ItemStack> f) {
        var wert = neu.getComponent(typ);
        if (wert.isEmpty()) {
            return false;
        }
        var alt = lesen.apply(wert.get());
        var liste = new ArrayList<>(alt.stream().map(f).toList());
        if (IntStream.range(0, alt.size()).allMatch(i -> liste.get(i) == alt.get(i))) {
            return false;
        }
        neu.setComponent(typ, bauen.apply(liste));
        return true;
    }

    /**
     * Creative-Paket: Auf jeder Ebene mit Token kommen die Komponenten zurück, die die Verschleierung ändert. Alles andere
     * bleibt vom Client, auch die Stückzahl und ein umgepacktes Bündel. Ohne Token kommt das Item selbst zurück.
     *
     * @throws NoSuchElementException bei einem Token, zu dem kein Original gemerkt ist
     */
    public static ItemStack wiederherstellen(ItemStack item, Function<String, ItemStack> originale) {
        if (item == null || item.isEmpty()) {
            return item;
        }
        var neu = item.copy();
        var geaendert = false;
        if (item.getComponent(ComponentTypes.CUSTOM_DATA).map(d -> d.getTagOrNull(TOKEN)).orElse(null)
                instanceof NBTString token) {
            var original = originale.apply(token.getValue());
            if (original == null) {
                throw new NoSuchElementException("unbekanntes Token " + token.getValue());
            }
            EIGENE.forEach(typ -> uebernehmen(neu, original, typ));
            geaendert = true;
        }
        return verschachtelt(neu, i -> wiederherstellen(i, originale)) | geaendert ? neu : item;
    }

    private static <T> void uebernehmen(ItemStack ziel, ItemStack quelle, ComponentType<T> typ) {
        ziel.setComponent(typ, quelle.getComponent(typ));
    }

    /** Entity-Metadaten: jeder Eintrag mit Item, erkannt am Wert. Diese Items schickt kein Client zurück. */
    @SuppressWarnings("unchecked")
    public boolean items(List<EntityData<?>> daten, Sicht sicht) {
        var geaendert = false;
        for (var eintrag : daten) {
            if (eintrag.getValue() instanceof ItemStack alt) {
                var neu = item(alt, sicht, (token, original) -> { });
                if (neu != alt) {
                    ((EntityData<Object>) eintrag).setValue(neu);
                    geaendert = true;
                }
            }
        }
        return geaendert;
    }

    /**
     * Items in Blockentity-NBT (Regal, Lagerfeuer, …), rekursiv: gesperrte Bücher und Schildtexte in Items werden zu
     * Runen. Das Schild selbst (oberste Ebene) ist nicht gemeint. true, wenn etwas geändert wurde.
     */
    public boolean itemsNbt(NBT nbt, Sicht sicht) {
        var geaendert = false;
        if (nbt instanceof NBTCompound c) {
            if (c.getTagOrNull("components") instanceof NBTCompound komponenten) {
                geaendert = komponentenNbt(komponenten, sicht);
            }
            for (var wert : c.getTags().values()) {
                geaendert |= itemsNbt(wert, sicht);
            }
        } else if (nbt instanceof NBTList<?> liste) {
            for (var wert : liste.getTags()) {
                geaendert |= itemsNbt(wert, sicht);
            }
        }
        return geaendert;
    }

    private boolean komponentenNbt(NBTCompound komponenten, Sicht sicht) {
        var geaendert = komponenten.getTagOrNull("minecraft:block_entity_data") instanceof NBTCompound be
                && schildNbt(be, sicht);
        var buecher = Stream.of(GESCHRIEBEN, BESCHREIBBAR).map(komponenten::getTagOrNull)
                .filter(NBTCompound.class::isInstance).map(NBTCompound.class::cast).toList();
        if (buecher.isEmpty()
                || lesbar(sicht, Art.BUECHER, kennzeichen(komponenten.getTagOrNull("minecraft:custom_data")))) {
            return geaendert;
        }
        for (var inhalt : buecher) {
            if (inhalt.getTagOrNull("title") instanceof NBT titel) {
                inhalt.setTag("title", filterbarNbt(titel));
            }
            if (inhalt.getTagOrNull("author") instanceof NBT autor) {
                inhalt.setTag("author", runenNbt(autor));
            }
            if (inhalt.getTagOrNull("pages") instanceof NBTList<?> seiten) {
                inhalt.setTag("pages", liste(seiten.getTags().stream().map(this::filterbarNbt).toList()));
            }
        }
        if (komponenten.getTagOrNull("minecraft:custom_name") instanceof NBT name) {
            komponenten.setTag("minecraft:custom_name", runenNbt(name));
        }
        return true;
    }

    /** Schild in Item-Daten: Die Kennzeichen stehen hier im eigenen PDC, nicht im Zwischenspeicher. */
    private boolean schildNbt(NBTCompound be, Sicht sicht) {
        if (!istSchild(be) || lesbar(sicht, Art.SCHILDER, kennzeichen(be))) {
            return false;
        }
        schild(be);
        return true;
    }

    /** Text oder Filterable ({raw, filtered}) im NBT-Format zu Runen. */
    private NBT filterbarNbt(NBT wert) {
        if (!(wert instanceof NBTCompound c) || !c.contains("raw")) {
            return runenNbt(wert);
        }
        var neu = c.copy();
        neu.setTag("raw", runenNbt(c.getTagOrThrow("raw")));
        if (c.getTagOrNull("filtered") instanceof NBT gefiltert) {
            neu.setTag("filtered", runenNbt(gefiltert));
        }
        return neu;
    }

    private NBTString runenNbt(NBT text) {
        return new NBTString(runen(text(text)));
    }

    /** Wie im Spiel: reine Strings als String-Liste, sonst Compound-Liste mit eingepackten Strings. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NBTList<NBT> liste(List<NBT> elemente) {
        NBTList<NBT> liste = new NBTList(elemente.stream().allMatch(NBTString.class::isInstance)
                ? NBTType.STRING : NBTType.COMPOUND);
        elemente.forEach(liste::addTagOrWrap);
        return liste;
    }

    private static boolean lesbar(Sicht sicht, Art art, Set<String> kennzeichen) {
        return sicht != null && kennzeichen != null && sicht.darfLesen(art, kennzeichen);
    }

    /**
     * Kennzeichen aus NBT mit PDC (custom_data eines Items oder Blockentity-Daten). Leer ohne Eintrag, null wenn
     * beschädigt: Dann ist das Objekt für alle mit Einschränkungen gesperrt.
     */
    static Set<String> kennzeichen(NBT halter) {
        if (halter == null) {
            return Set.of();
        }
        if (!(halter instanceof NBTCompound c)) {
            return null;
        }
        if (!(c.getTagOrNull("PublicBukkitValues") instanceof NBT pdc)) {
            return Set.of();
        }
        if (!(pdc instanceof NBTCompound p)) {
            return null;
        }
        if (!(p.getTagOrNull(Kennzeichen.SCHLUESSEL.asString()) instanceof NBT liste)) {
            return Set.of();
        }
        if (!(liste instanceof NBTList<?> l) || !l.getTags().stream().allMatch(NBTString.class::isInstance)) {
            return null;
        }
        return l.getTags().stream().map(t -> ((NBTString) t).getValue()).collect(Collectors.toUnmodifiableSet());
    }

    public static boolean istSchild(NBTCompound nbt) {
        return nbt.contains("front_text") || nbt.contains("back_text");
    }

    /** Ersetzt jede Zeile beider Seiten durch Runen gleicher Länge. Farbe, Leuchttinte usw. bleiben. */
    public void schild(NBTCompound nbt) {
        for (var seite : SEITEN) {
            var text = nbt.getCompoundTagOrNull(seite);
            if (text == null) {
                continue;
            }
            for (var schluessel : ZEILEN) {
                if (text.getTagOrNull(schluessel) instanceof NBTList<?> zeilen) {
                    var neu = new NBTList<>(NBTType.STRING);
                    zeilen.getTags().forEach(zeile -> neu.addTag(new NBTString(runen(text(zeile)))));
                    text.setTag(schluessel, neu);
                }
            }
        }
    }

    /**
     * Ersetzt in den Metadaten einer Entity den Custom Name (Index 2) durch Runen. Leerer oder fehlender Name und alle
     * anderen Einträge bleiben. true, wenn etwas geändert wurde. Je nach Typ liegt der Name als Component oder als
     * JSON-String vor.
     */
    @SuppressWarnings("unchecked")
    public boolean mobname(List<EntityData<?>> daten) {
        var geaendert = false;
        for (var eintrag : daten) {
            if (eintrag.getIndex() != INDEX_MOBNAME || !(eintrag.getValue() instanceof Optional<?> name)
                    || name.isEmpty()) {
                continue;
            }
            var json = name.get() instanceof String;
            var klartext = PlainTextComponentSerializer.plainText().serialize(json
                    ? GsonComponentSerializer.gson().deserialize((String) name.get()) : (Component) name.get());
            if (klartext.isEmpty()) {
                continue;
            }
            var runen = Component.text(runen(klartext));
            ((EntityData<Object>) eintrag).setValue(Optional.of(json ? GsonComponentSerializer.gson().serialize(runen) : runen));
            geaendert = true;
        }
        return geaendert;
    }

    /** Ungefährer Klartext einer Textkomponente im NBT-Format: alle Textinhalte aneinandergehängt. */
    public static String text(NBT komponente) {
        var text = new StringBuilder();
        sammeln(komponente, text);
        return text.toString();
    }

    private static void sammeln(NBT nbt, StringBuilder text) {
        switch (nbt) {
            case NBTString s -> text.append(s.getValue());
            case NBTList<?> liste -> liste.getTags().forEach(t -> sammeln(t, text));
            case NBTCompound c -> c.getTags().forEach((schluessel, wert) -> {
                if (!(wert instanceof NBTString) || TEXTSCHLUESSEL.contains(schluessel)) {
                    sammeln(wert, text);
                }
            });
            default -> { }
        }
    }
}
