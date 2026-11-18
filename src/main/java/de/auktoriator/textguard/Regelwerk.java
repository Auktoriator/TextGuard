package de.auktoriator.textguard;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** Alle Gruppen, unveränderlich. Änderungen liefern ein neues Regelwerk. Die Gruppe „standard“ gibt es immer. */
public record Regelwerk(Map<String, Gruppe> gruppen) {

    public static final String STANDARD = "standard";
    public static final Regelwerk LEER = new Regelwerk(Map.of());
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    public Regelwerk {
        var sortiert = new TreeMap<>(gruppen);
        sortiert.putIfAbsent(STANDARD, new Gruppe(STANDARD, Set.of(), Map.of()));
        gruppen = Collections.unmodifiableMap(sortiert);
    }

    /** Gilt für Gruppennamen und Kennzeichen. */
    public static boolean gueltigerName(String name) {
        return NAME.matcher(name).matches();
    }

    /** Alle Kennzeichen, die in einer Regel als Ausnahme vorkommen, sortiert. */
    public SortedSet<String> kennzeichen() {
        var alle = new TreeSet<String>();
        gruppen.values().forEach(g -> g.regeln().values().forEach(r -> alle.addAll(r.ausnahmen())));
        return alle;
    }

    public Regelwerk mitGruppe(Gruppe gruppe) {
        var neu = new TreeMap<>(gruppen);
        neu.put(gruppe.name(), gruppe);
        return new Regelwerk(neu);
    }

    public Regelwerk ohneGruppe(String name) {
        if (name.equals(STANDARD)) {
            throw new IllegalArgumentException("Die Gruppe „standard“ kann nicht gelöscht werden.");
        }
        var neu = new TreeMap<>(gruppen);
        neu.remove(name);
        return new Regelwerk(neu);
    }

    /**
     * Gruppen des Spielers: UUID-Mitglied oder Recht {@code textguard.gruppe.<name>} (rechteGruppen).
     * Ohne Gruppe gilt „standard“.
     */
    public Sicht sicht(UUID spieler, boolean bypass, Set<String> rechteGruppen) {
        var seine = gruppen.values().stream()
                .filter(g -> g.mitglieder().contains(spieler) || rechteGruppen.contains(g.name()))
                .toList();
        return sicht(bypass, seine.isEmpty() ? List.of(gruppen.get(STANDARD)) : seine);
    }

    /** Testansicht: nur diese Gruppe, kein Bypass. */
    public Sicht sichtDerGruppe(String name) {
        return sicht(false, List.of(gruppen.get(name)));
    }

    private static Sicht sicht(boolean bypass, List<Gruppe> gruppen) {
        var regeln = new EnumMap<Art, List<Regel>>(Art.class);
        for (var art : Art.values()) {
            regeln.put(art, gruppen.stream().map(g -> g.regel(art)).toList());
        }
        return new Sicht(bypass, regeln);
    }
}
