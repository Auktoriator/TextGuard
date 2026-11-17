package de.auktoriator.textguard;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Zentrale Ablage von Regelwerk und Sichten. Lesen ist aus Netty-Threads erlaubt, alles andere nur im Hauptthread. */
public final class Sichten {

    private static final String RECHT_BYPASS = "textguard.bypass";
    private static final String RECHT_ADMIN = "textguard.admin";
    private static final String RECHT_GRUPPE = "textguard.gruppe.";

    private final OpStufe ops;
    private volatile Regelwerk regelwerk = Regelwerk.LEER;
    private final Map<UUID, Sicht> sichten = new ConcurrentHashMap<>();
    private final Map<UUID, String> testansichten = new ConcurrentHashMap<>();

    public Sichten(OpStufe ops) {
        this.ops = ops;
    }

    public Regelwerk regelwerk() {
        return regelwerk;
    }

    public void regelwerk(Regelwerk neu) {
        regelwerk = neu;
    }

    public OpStufe ops() {
        return ops;
    }

    /**
     * Für Netty-Threads, ohne Bukkit-Aufruf. Fehlt die gespeicherte Sicht (Pakete vor dem Join), wird sie
     * fail-closed nur aus UUID-Mitgliedschaften und ops.json berechnet, nicht gespeichert.
     */
    public Sicht sicht(UUID spieler) {
        var gespeichert = sichten.get(spieler);
        return gespeichert != null ? gespeichert : regelwerk.sicht(spieler, ops.stufe(spieler) >= 4, Set.of());
    }

    /** Hauptthread. Berechnet die Sicht neu und speichert sie; true, wenn sie sich geändert hat. */
    public boolean berechne(Player spieler) {
        var uuid = spieler.getUniqueId();
        var testgruppe = testansichten.get(uuid);
        if (testgruppe != null && !regelwerk.gruppen().containsKey(testgruppe)) {
            testansichten.remove(uuid);
            testgruppe = null;
        }
        Sicht neu;
        if (testgruppe != null) {
            neu = regelwerk.sichtDerGruppe(testgruppe);
        } else {
            var bypass = ops.stufe(uuid) >= 4 || spieler.hasPermission(RECHT_BYPASS);
            var rechteGruppen = regelwerk.gruppen().keySet().stream()
                    .filter(name -> spieler.hasPermission(RECHT_GRUPPE + name))
                    .collect(Collectors.toSet());
            neu = regelwerk.sicht(uuid, bypass, rechteGruppen);
        }
        return !neu.equals(sichten.put(uuid, neu));
    }

    public void entfernen(UUID spieler) {
        sichten.remove(spieler);
        testansichten.remove(spieler);
    }

    /** Gruppe für die Testansicht oder null zum Beenden. */
    public void testansicht(UUID spieler, String gruppe) {
        if (gruppe == null) {
            testansichten.remove(spieler);
        } else {
            testansichten.put(spieler, gruppe);
        }
    }

    public static boolean istAdmin(CommandSender sender, OpStufe ops) {
        return !(sender instanceof Player p) || ops.stufe(p.getUniqueId()) >= 4 || p.hasPermission(RECHT_ADMIN);
    }
}
