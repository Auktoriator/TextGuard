package de.auktoriator.textguard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpStufeTest {

    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String JSON = """
            [{"uuid":"00000000-0000-0000-0000-000000000001","name":"Anna","level":4,"bypassesPlayerLimit":false},
             {"name":"ohne uuid","level":2}]""";

    @TempDir
    Path ordner;

    @Test
    void parsen() {
        var stufen = OpStufe.parse(JSON);
        assertEquals(1, stufen.size());
        assertEquals(4, stufen.get(ANNA));
        assertThrows(RuntimeException.class, () -> OpStufe.parse("kein json ["));
    }

    @Test
    void liestNurBeiNeuemZeitstempel() throws IOException {
        var datei = ordner.resolve("ops.json");
        var ops = new OpStufe(datei);
        assertTrue(ops.neuLesen());
        assertEquals(0, ops.stufe(ANNA));

        Files.writeString(datei, JSON);
        Files.setLastModifiedTime(datei, FileTime.fromMillis(1_000_000));
        assertTrue(ops.neuLesen());
        assertEquals(4, ops.stufe(ANNA));
        assertFalse(ops.neuLesen());

        Files.writeString(datei, "[]");
        Files.setLastModifiedTime(datei, FileTime.fromMillis(2_000_000));
        assertTrue(ops.neuLesen());
        assertEquals(0, ops.stufe(ANNA));
    }
}
