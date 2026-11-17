package de.auktoriator.textguard;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Grundsatz (alle/keine lesbar) plus Ausnahme-Kennzeichen, die das Ergebnis umkehren. */
public record Regel(boolean grundsatz, Set<String> ausnahmen) {

    public static final Regel ALLE_LESBAR = new Regel(true, Set.of());

    public Regel {
        ausnahmen = Set.copyOf(ausnahmen);
    }

    public boolean erlaubt(Set<String> kennzeichen) {
        return grundsatz ^ !Collections.disjoint(kennzeichen, ausnahmen);
    }

    public Regel mitGrundsatz(boolean neu) {
        return new Regel(neu, ausnahmen);
    }

    public Regel mitAusnahme(String kennzeichen) {
        var neu = new HashSet<>(ausnahmen);
        neu.add(kennzeichen);
        return new Regel(grundsatz, neu);
    }

    public Regel ohneAusnahme(String kennzeichen) {
        var neu = new HashSet<>(ausnahmen);
        neu.remove(kennzeichen);
        return new Regel(grundsatz, neu);
    }

    /** z. B. „alle außer geheim“ oder „keine außer quest1“. */
    public String klartext() {
        var text = grundsatz ? "alle" : "keine";
        return ausnahmen.isEmpty() ? text : text + " außer " + String.join(", ", new TreeSet<>(ausnahmen));
    }
}
