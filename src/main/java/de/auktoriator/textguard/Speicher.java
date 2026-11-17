package de.auktoriator.textguard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Liest und schreibt gruppen.yml und das Geheimnis für die Runen. */
public final class Speicher {

    private final Path datei;

    public Speicher(Path datei) {
        this.datei = datei;
    }

    /** Fehlt die Datei, gibt es nur „standard“. Bei Fehlern in der Datei: IllegalArgumentException mit Erklärung. */
    public Regelwerk laden() throws IOException {
        if (!Files.exists(datei)) {
            return Regelwerk.LEER;
        }
        var yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(datei));
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("Syntaxfehler in gruppen.yml: " + e.getMessage(), e);
        }
        var gruppen = new HashMap<String, Gruppe>();
        var abschnitt = yaml.getConfigurationSection("gruppen");
        if (abschnitt != null) {
            for (var name : abschnitt.getKeys(false)) {
                try {
                    gruppen.put(name, leseGruppe(name, abschnitt.getConfigurationSection(name)));
                } catch (IllegalArgumentException | NullPointerException e) {
                    throw new IllegalArgumentException("Gruppe „" + name + "“: " + e.getMessage(), e);
                }
            }
        }
        return new Regelwerk(gruppen);
    }

    private static Gruppe leseGruppe(String name, ConfigurationSection g) {
        if (!Regelwerk.gueltigerName(name)) {
            throw new IllegalArgumentException("ungültiger Name (erlaubt: a-z, 0-9, _ und -, höchstens 32 Zeichen)");
        }
        if (g == null) {
            throw new IllegalArgumentException("Eintrag ist leer oder kein Abschnitt");
        }
        var mitglieder = new HashSet<UUID>();
        for (var uuid : g.getStringList("mitglieder")) {
            mitglieder.add(UUID.fromString(uuid));
        }
        var regeln = new HashMap<Art, Regel>();
        var regelAbschnitt = g.getConfigurationSection("regeln");
        if (regelAbschnitt != null) {
            for (var schluessel : regelAbschnitt.getKeys(false)) {
                var art = Art.von(schluessel)
                        .orElseThrow(() -> new IllegalArgumentException("unbekannte Art „" + schluessel + "“"));
                var r = regelAbschnitt.getConfigurationSection(schluessel);
                if (r == null) {
                    throw new IllegalArgumentException("Regel „" + schluessel + "“ ist kein Abschnitt");
                }
                var grundsatz = switch (String.valueOf(r.getString("grundsatz"))) {
                    case "alle" -> true;
                    case "keine" -> false;
                    default -> throw new IllegalArgumentException(
                            "Grundsatz von „" + schluessel + "“ muss „alle“ oder „keine“ sein");
                };
                var ausnahmen = new HashSet<String>();
                for (var kennzeichen : r.getStringList("ausnahmen")) {
                    if (!Regelwerk.gueltigerName(kennzeichen)) {
                        throw new IllegalArgumentException("ungültiges Kennzeichen „" + kennzeichen + "“");
                    }
                    ausnahmen.add(kennzeichen);
                }
                regeln.put(art, new Regel(grundsatz, ausnahmen));
            }
        }
        return new Gruppe(name, mitglieder, regeln);
    }

    /**
     * 32 zufällige Bytes, beim ersten Aufruf angelegt. Temp-Dateien sind unter Linux und macOS nur für den Besitzer
     * lesbar (600), das Umbenennen behält das bei.
     */
    public static byte[] geheimnis(Path datei) throws IOException {
        if (Files.exists(datei) && Files.size(datei) == 32) {
            return Files.readAllBytes(datei);
        }
        var neu = new byte[32];
        new SecureRandom().nextBytes(neu);
        var ordner = datei.toAbsolutePath().getParent();
        Files.createDirectories(ordner);
        var temp = Files.createTempFile(ordner, "geheimnis", ".tmp");
        try {
            Files.write(temp, neu);
            Files.move(temp, datei, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return neu;
    }

    /** Atomar: erst in eine Temp-Datei, dann umbenennen, damit nie eine halbe Datei liegt. */
    public void speichern(Regelwerk regelwerk) throws IOException {
        var yaml = new YamlConfiguration();
        for (var gruppe : regelwerk.gruppen().values()) {
            var pfad = "gruppen." + gruppe.name();
            yaml.set(pfad + ".mitglieder", gruppe.mitglieder().stream().map(UUID::toString).sorted().toList());
            yaml.createSection(pfad + ".regeln");
            for (var art : Art.values()) {
                if (gruppe.regeln().containsKey(art)) {
                    var regel = gruppe.regel(art);
                    var regelPfad = pfad + ".regeln." + art.befehl();
                    yaml.set(regelPfad + ".grundsatz", regel.grundsatz() ? "alle" : "keine");
                    List<String> ausnahmen = regel.ausnahmen().stream().sorted().toList();
                    yaml.set(regelPfad + ".ausnahmen", ausnahmen);
                }
            }
        }
        var ordner = datei.toAbsolutePath().getParent();
        Files.createDirectories(ordner);
        var temp = Files.createTempFile(ordner, "gruppen", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString());
            Files.move(temp, datei, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
