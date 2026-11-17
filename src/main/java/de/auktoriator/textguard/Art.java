package de.auktoriator.textguard;

import java.util.Arrays;
import java.util.Optional;

/** Texte, deren Lesbarkeit pro Gruppe geregelt wird. */
public enum Art {
    SCHILDER("schilder"),
    BUECHER("buecher"),
    MOBNAMEN("mobnamen");

    private final String befehl;

    Art(String befehl) {
        this.befehl = befehl;
    }

    /** Name in Befehlen und in gruppen.yml. */
    public String befehl() {
        return befehl;
    }

    public static Optional<Art> von(String befehl) {
        return Arrays.stream(values()).filter(a -> a.befehl.equals(befehl)).findFirst();
    }
}
