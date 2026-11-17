package de.auktoriator.textguard;

import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Macht Texte unlesbar. Rein (kein Bukkit, nur das Server-Geheimnis), deshalb auch in Netty-Threads nutzbar. */
public final class Verschleierung {

    private static final int ERSTE_RUNE = 0x16A0;
    private static final int RUNEN = 0x16EA - ERSTE_RUNE + 1;
    private static final List<String> SEITEN = List.of("front_text", "back_text");
    private static final List<String> ZEILEN = List.of("messages", "filtered_messages");
    // "" ist der Schlüssel, unter dem gemischte NBT-Listen einzelne Strings einpacken.
    private static final Set<String> TEXTSCHLUESSEL = Set.of("text", "translate", "fallback", "keybind", "");

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
        byte[] hash;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(geheimnis);
            hash = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 fehlt", e);
        }
        var zufall = new SplittableRandom(ByteBuffer.wrap(hash).getLong());
        var runen = new StringBuilder();
        text.codePoints().forEach(c -> runen.appendCodePoint(ERSTE_RUNE + zufall.nextInt(RUNEN)));
        return runen.toString();
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
