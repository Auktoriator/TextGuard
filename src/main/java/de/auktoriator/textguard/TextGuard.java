package de.auktoriator.textguard;

import com.github.retrooper.packetevents.PacketEvents;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class TextGuard extends JavaPlugin implements Listener {

    private Speicher speicher;
    private OpStufe ops;
    private Sichten sichten;
    private Auffrischen auffrischen;
    private Kennzeichen kennzeichen;
    private PaketFilter paketFilter;

    @Override
    public void onEnable() {
        speicher = new Speicher(getDataFolder().toPath().resolve("gruppen.yml"));
        ops = new OpStufe(Path.of("ops.json"));
        sichten = new Sichten(ops);
        kennzeichen = new Kennzeichen(this);
        auffrischen = new Auffrischen(sichten, kennzeichen);
        byte[] geheimnis;
        try {
            ops.neuLesen();
            sichten.regelwerk(speicher.laden());
            geheimnis = Speicher.geheimnis(getDataFolder().toPath().resolve("geheimnis"));
        } catch (IOException | RuntimeException e) {
            // Mit leeren Regeln weiterzulaufen würde die kaputte Datei beim nächsten Speichern überschreiben.
            getLogger().severe("gruppen.yml, ops.json oder geheimnis nicht lesbar, TextGuard wird beendet: "
                    + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getOnlinePlayers().forEach(kennzeichen::welt);
        // Erst der Lauscher, dann das Durchsehen: Was dazwischen rausgeht, ist verschleiert und wird nachgesendet.
        paketFilter = new PaketFilter(sichten, kennzeichen, new Verschleierung(geheimnis), getLogger());
        PacketEvents.getAPI().getEventManager().registerListener(paketFilter);
        getServer().getWorlds().forEach(welt -> List.of(welt.getLoadedChunks()).forEach(kennzeichen::durchsehen));
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(kennzeichen, this);
        kennzeichen.entitiesDurchsehen();
        getServer().getPluginManager().registerEvents(new Schutz(sichten, kennzeichen), this);
        getServer().getScheduler().runTaskTimer(this, kennzeichen::unbekannteDurchsehen, 1, 1);
        var befehle = new Befehle(this, sichten, auffrischen, kennzeichen);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(befehle.baum(), "Steuert, wer welche Texte lesen darf.", List.of("tg")));
        getServer().getScheduler().runTaskTimer(this, this::pruefen, 100, 100);
        getLogger().info("TextGuard ist aktiv.");
    }

    @Override
    public void onDisable() {
        if (paketFilter != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(paketFilter);
        }
    }

    /** Speichert und übernimmt ein geändertes Regelwerk. Bei Speicherfehler bleibt alles beim Alten. */
    void setzen(Regelwerk neu) throws IOException {
        speicher.speichern(neu);
        uebernehmen(neu);
    }

    /** Liest gruppen.yml und ops.json neu. Bei einem Fehler bleibt das alte Regelwerk, der Fehler geht an den Absender. */
    void neuladen(CommandSender absender) {
        try {
            ops.neuLesen();
            uebernehmen(speicher.laden());
            absender.sendMessage(Component.text("Gruppen neu geladen.",
                    NamedTextColor.GREEN));
        } catch (IOException | RuntimeException e) {
            getLogger().warning("Neuladen fehlgeschlagen: " + e.getMessage());
            absender.sendMessage(Component.text(
                    "Neuladen fehlgeschlagen, die alten Regeln bleiben: " + e.getMessage(),
                    NamedTextColor.RED));
        }
    }

    private void uebernehmen(Regelwerk neu) {
        sichten.regelwerk(neu);
        auffrischen.alle(false);
    }

    /** Alle 5 Sekunden: OP-Stufen und Rechte können sich ohne unseren Befehl geändert haben. */
    private void pruefen() {
        try {
            ops.neuLesen();
        } catch (IOException | RuntimeException e) {
            getLogger().warning("ops.json nicht lesbar: " + e.getMessage());
        }
        auffrischen.alle(true);
    }

    @EventHandler
    public void beimBeitritt(PlayerJoinEvent event) {
        kennzeichen.welt(event.getPlayer());
        sichten.berechne(event.getPlayer());
        auffrischen.spieler(event.getPlayer());
    }

    // Pakete aus der neuen Welt, die schon raus sind, wurden mit der alten Welt geprüft.
    @EventHandler
    public void beimWeltwechsel(PlayerChangedWorldEvent event) {
        kennzeichen.welt(event.getPlayer());
        auffrischen.spieler(event.getPlayer());
    }

    @EventHandler
    public void beimVerlassen(PlayerQuitEvent event) {
        sichten.entfernen(event.getPlayer().getUniqueId());
        auffrischen.entfernen(event.getPlayer().getUniqueId());
        kennzeichen.entfernen(event.getPlayer().getUniqueId());
    }
}
