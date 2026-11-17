package de.auktoriator.textguard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fehlt für eine Art die Regel, sind alle Texte dieser Art lesbar. */
public record Gruppe(String name, Set<UUID> mitglieder, Map<Art, Regel> regeln) {

    public Gruppe {
        mitglieder = Set.copyOf(mitglieder);
        regeln = Map.copyOf(regeln);
    }

    public Regel regel(Art art) {
        return regeln.getOrDefault(art, Regel.ALLE_LESBAR);
    }

    public Gruppe mitMitglied(UUID uuid) {
        var neu = new HashSet<>(mitglieder);
        neu.add(uuid);
        return new Gruppe(name, neu, regeln);
    }

    public Gruppe ohneMitglied(UUID uuid) {
        var neu = new HashSet<>(mitglieder);
        neu.remove(uuid);
        return new Gruppe(name, neu, regeln);
    }

    public Gruppe mitRegel(Art art, Regel regel) {
        var neu = new HashMap<>(regeln);
        neu.put(art, regel);
        return new Gruppe(name, mitglieder, neu);
    }
}
