package de.auktoriator.textguard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class WerkzeugTest {

    @Test
    void kennzeichenAusAllenRegelnSortiert() {
        var werk = Regelwerk.LEER
                .mitGruppe(new Gruppe("a", Set.of(), Map.of(Art.SCHILDER, new Regel(true, Set.of("quest1", "geheim")))))
                .mitGruppe(new Gruppe("b", Set.of(), Map.of(Art.MOBNAMEN, new Regel(false, Set.of("geheim", "boss")))));
        assertEquals(new TreeSet<>(Set.of("boss", "geheim", "quest1")), werk.kennzeichen());
        assertTrue(Regelwerk.LEER.kennzeichen().isEmpty());
    }

    @Test
    void naechstesKennzeichenRundum() {
        var alle = new TreeSet<>(Set.of("boss", "geheim", "geheim2"));
        assertEquals("boss", Werkzeug.naechstes(alle, ""));
        assertEquals("geheim", Werkzeug.naechstes(alle, "boss"));
        assertEquals("geheim2", Werkzeug.naechstes(alle, "geheim"));
        assertEquals("boss", Werkzeug.naechstes(alle, "geheim2"));
        // Nicht mehr in den Regeln: das nächste danach.
        assertEquals("geheim", Werkzeug.naechstes(alle, "c"));
    }
}
