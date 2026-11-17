package de.auktoriator.textguard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpeicherTest {

    @TempDir
    Path ordner;

    @Test
    void rundlauf() throws IOException {
        var speicher = new Speicher(ordner.resolve("unter/gruppen.yml"));
        var gruppe = new Gruppe("c", Set.of(UUID.randomUUID()),
                Map.of(Art.SCHILDER, new Regel(false, Set.of("quest1")), Art.BUECHER, new Regel(true, Set.of())));
        var werk = Regelwerk.LEER.mitGruppe(gruppe);
        speicher.speichern(werk);
        assertEquals(werk, speicher.laden());
        assertEquals(1, Files.list(ordner.resolve("unter")).count(), "keine Temp-Datei übrig");
    }

    @Test
    void fehlendeDateiGibtNurStandard() throws IOException {
        assertEquals(Regelwerk.LEER, new Speicher(ordner.resolve("gibtsnicht.yml")).laden());
    }

    @Test
    void kaputteDateiWirftUndAlteDateiBleibt() throws IOException {
        var datei = ordner.resolve("gruppen.yml");
        var speicher = new Speicher(datei);
        for (var kaputt : new String[] {
                "gruppen: [unvollstaendig",
                "gruppen:\n  Gross:\n    mitglieder: []\n",
                "gruppen:\n  a:\n    mitglieder: [keine-uuid]\n",
                "gruppen:\n  a:\n    regeln:\n      unbekannt:\n        grundsatz: alle\n",
                "gruppen:\n  a:\n    regeln:\n      schilder:\n        grundsatz: vielleicht\n",
                "gruppen:\n  a:\n    regeln:\n      schilder:\n        grundsatz: alle\n        ausnahmen: [GROSS]\n"}) {
            Files.writeString(datei, kaputt);
            assertThrows(IllegalArgumentException.class, speicher::laden, kaputt);
        }
        assertTrue(Files.readString(datei).contains("GROSS"), "Laden verändert die Datei nicht");
    }
}
