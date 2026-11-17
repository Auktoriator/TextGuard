package de.auktoriator.textguard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KennzeichenTest {

    private final Kennzeichen kz = new Kennzeichen(null);
    private final Sicht alleAusserGeheim = new Sicht(false,
            Map.of(Art.MOBNAMEN, java.util.List.of(new Regel(true, Set.of("geheim")))));
    private final Sicht keineAusserQuest = new Sicht(false,
            Map.of(Art.MOBNAMEN, java.util.List.of(new Regel(false, Set.of("quest")))));

    @Test
    void unbekannteIdHatKeineKennzeichen() {
        assertTrue(kz.mobnameLesbar(alleAusserGeheim, 1));
        assertFalse(kz.mobnameLesbar(keineAusserQuest, 1));
    }

    @Test
    void kennzeichenKehrenDieRegelUm() {
        kz.entityEintragen(1, Set.of("geheim"));
        kz.entityEintragen(2, Set.of("quest"));
        assertFalse(kz.mobnameLesbar(alleAusserGeheim, 1));
        assertTrue(kz.mobnameLesbar(keineAusserQuest, 2));
    }

    @Test
    void defekterEintragIstFuerAlleGesperrt() {
        kz.entityEintragen(1, null);
        assertFalse(kz.mobnameLesbar(alleAusserGeheim, 1));
        assertFalse(kz.mobnameLesbar(keineAusserQuest, 1));
    }

    @Test
    void leereKennzeichenLoeschenDenEintrag() {
        kz.entityEintragen(1, Set.of("geheim"));
        kz.entityEintragen(1, Set.of());
        assertTrue(kz.mobnameLesbar(alleAusserGeheim, 1));
    }
}
