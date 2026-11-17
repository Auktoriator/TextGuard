package de.auktoriator.textguard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Bringt Spieler nach einer Änderung auf den aktuellen Stand. Nur im Hauptthread aufrufen. */
public final class Auffrischen {

    private final Sichten sichten;
    private final Map<UUID, Boolean> admins = new ConcurrentHashMap<>();

    public Auffrischen(Sichten sichten) {
        this.sichten = sichten;
    }

    /** Berechnet die Sichten aller Online-Spieler neu, bei nurGeaenderte nur diese auffrischen. */
    public void alle(boolean nurGeaenderte) {
        for (var spieler : Bukkit.getOnlinePlayers()) {
            if (sichten.berechne(spieler) || !nurGeaenderte) {
                spieler(spieler);
            } else {
                adminAbgleichen(spieler);
            }
        }
    }

    public void spieler(Player spieler) {
        adminAbgleichen(spieler);
        // Hier ergänzen spätere Schritte das Nachsenden von Schildern, Entities und Inventar.
    }

    public void entfernen(UUID spieler) {
        admins.remove(spieler);
    }

    // Die Befehle hängen am Admin-Recht, der Client kennt den Baum aber nur vom letzten Senden.
    private void adminAbgleichen(Player spieler) {
        var admin = Sichten.istAdmin(spieler, sichten.ops());
        var vorher = admins.put(spieler.getUniqueId(), admin);
        if (vorher != null && vorher != admin) {
            spieler.updateCommands();
        }
    }
}
