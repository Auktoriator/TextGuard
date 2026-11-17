package de.auktoriator.textguard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegelwerkTest {

    private static final UUID ANNA = UUID.randomUUID();
    private static final Set<String> GEHEIM = Set.of("geheim");
    private static final Set<String> OHNE = Set.of();

    @Test
    void regelIstGrundsatzXorAusnahme() {
        var alleAusserGeheim = new Regel(true, GEHEIM);
        assertTrue(alleAusserGeheim.erlaubt(OHNE));
        assertFalse(alleAusserGeheim.erlaubt(GEHEIM));
        var keineAusserQuest = new Regel(false, Set.of("quest1"));
        assertFalse(keineAusserQuest.erlaubt(OHNE));
        assertFalse(keineAusserQuest.erlaubt(GEHEIM));
        assertTrue(keineAusserQuest.erlaubt(Set.of("quest1", "geheim")));
        assertEquals("keine außer quest1", keineAusserQuest.klartext());
    }

    @Test
    void standardGibtEsImmerUndIstOhneRegelnAllesLesbar() {
        var werk = Regelwerk.LEER;
        assertTrue(werk.gruppen().containsKey("standard"));
        assertThrows(IllegalArgumentException.class, () -> werk.ohneGruppe("standard"));
        var sicht = werk.sicht(ANNA, false, Set.of());
        for (var art : Art.values()) {
            assertTrue(sicht.allesLesbar(art));
            assertTrue(sicht.darfLesen(art, GEHEIM));
        }
    }

    @Test
    void ohneGruppeGiltStandard() {
        var standard = new Gruppe("standard", Set.of(), Map.of(Art.SCHILDER, new Regel(false, Set.of())));
        var sicht = Regelwerk.LEER.mitGruppe(standard).sicht(ANNA, false, Set.of());
        assertFalse(sicht.darfLesen(Art.SCHILDER, OHNE));
        assertTrue(sicht.darfLesen(Art.BUECHER, OHNE));
    }

    @Test
    void grosszuegigsteGruppeGewinnt() {
        var streng = new Gruppe("streng", Set.of(ANNA), Map.of(Art.SCHILDER, new Regel(false, Set.of())));
        var quest = new Gruppe("quest", Set.of(), Map.of(Art.SCHILDER, new Regel(false, Set.of("quest1"))));
        var werk = Regelwerk.LEER.mitGruppe(streng).mitGruppe(quest);

        var nurStreng = werk.sicht(ANNA, false, Set.of());
        assertFalse(nurStreng.darfLesen(Art.SCHILDER, Set.of("quest1")));
        assertFalse(nurStreng.allesLesbar(Art.SCHILDER));

        var mitRecht = werk.sicht(ANNA, false, Set.of("quest"));
        assertTrue(mitRecht.darfLesen(Art.SCHILDER, Set.of("quest1")));
        assertFalse(mitRecht.darfLesen(Art.SCHILDER, OHNE));
    }

    @Test
    void bypassUndTestansicht() {
        var streng = new Gruppe("streng", Set.of(ANNA), Map.of(Art.BUECHER, new Regel(false, Set.of())));
        var werk = Regelwerk.LEER.mitGruppe(streng);
        var bypass = werk.sicht(ANNA, true, Set.of());
        assertTrue(bypass.darfLesen(Art.BUECHER, OHNE));
        assertTrue(bypass.allesLesbar(Art.BUECHER));
        assertTrue(Sicht.ALLES.allesLesbar(Art.MOBNAMEN));
        assertFalse(werk.sichtDerGruppe("streng").darfLesen(Art.BUECHER, OHNE));
    }

    @Test
    void allesLesbarBrauchtGrundsatzAlleOhneAusnahmen() {
        var gruppe = new Gruppe("g", Set.of(ANNA), Map.of(Art.SCHILDER, new Regel(true, GEHEIM)));
        var sicht = Regelwerk.LEER.mitGruppe(gruppe).sicht(ANNA, false, Set.of());
        assertFalse(sicht.allesLesbar(Art.SCHILDER));
        assertTrue(sicht.allesLesbar(Art.BUECHER));
    }

    @Test
    void namenPruefen() {
        assertTrue(Regelwerk.gueltigerName("quest_1-a"));
        assertFalse(Regelwerk.gueltigerName("Quest"));
        assertFalse(Regelwerk.gueltigerName(""));
        assertFalse(Regelwerk.gueltigerName("a".repeat(33)));
    }
}
