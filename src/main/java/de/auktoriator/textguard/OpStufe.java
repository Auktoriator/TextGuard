package de.auktoriator.textguard;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** OP-Stufen aus ops.json, weil Paper dafür keine API hat. Lesen ist aus jedem Thread erlaubt. */
public final class OpStufe {

    private final Path datei;
    private volatile Map<UUID, Integer> stufen = Map.of();
    private long gelesenZeit = -1;

    public OpStufe(Path datei) {
        this.datei = datei;
    }

    public int stufe(UUID uuid) {
        return stufen.getOrDefault(uuid, 0);
    }

    /** Nur Hauptthread. Liest neu, wenn sich der Zeitstempel geändert hat; true, wenn neu gelesen wurde. */
    public boolean neuLesen() throws IOException {
        long zeit = Files.exists(datei) ? Files.getLastModifiedTime(datei).toMillis() : 0;
        if (zeit == gelesenZeit) {
            return false;
        }
        stufen = zeit == 0 ? Map.of() : parse(Files.readString(datei));
        // Erst nach erfolgreichem Parsen merken, damit eine halb geschriebene Datei erneut versucht wird.
        gelesenZeit = zeit;
        return true;
    }

    static Map<UUID, Integer> parse(String json) {
        var ergebnis = new HashMap<UUID, Integer>();
        for (var element : JsonParser.parseString(json).getAsJsonArray()) {
            var eintrag = element.getAsJsonObject();
            if (eintrag.has("uuid") && eintrag.has("level")) {
                ergebnis.put(UUID.fromString(eintrag.get("uuid").getAsString()), eintrag.get("level").getAsInt());
            }
        }
        return Map.copyOf(ergebnis);
    }
}
