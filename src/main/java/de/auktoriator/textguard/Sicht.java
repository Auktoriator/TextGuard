package de.auktoriator.textguard;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Was ein Spieler lesen darf: pro Art alle Regeln seiner Gruppen, die großzügigste gewinnt. */
public record Sicht(boolean bypass, Map<Art, List<Regel>> regeln) {

    public static final Sicht ALLES = new Sicht(true, Map.of());

    public Sicht {
        var kopie = new EnumMap<Art, List<Regel>>(Art.class);
        regeln.forEach((art, liste) -> kopie.put(art, List.copyOf(liste)));
        regeln = Map.copyOf(kopie);
    }

    public boolean darfLesen(Art art, Set<String> kennzeichen) {
        return bypass || regeln.getOrDefault(art, List.of()).stream().anyMatch(r -> r.erlaubt(kennzeichen));
    }

    /** Schnellweg für Paketlauscher: dann muss nichts dekodiert werden. */
    public boolean allesLesbar(Art art) {
        return bypass || regeln.getOrDefault(art, List.of()).stream()
                .anyMatch(r -> r.grundsatz() && r.ausnahmen().isEmpty());
    }
}
