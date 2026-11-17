# TextGuard

Paper-Plugin, das pro Spielergruppe verschleiert, welche Texte (Schilder, Bücher, Mob-Namen) lesbar sind. Beschreibung und Plan: siehe [README](README.md).

## Bauen

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
./gradlew build
```

Lokal läuft Homebrew-OpenJDK 27, kompiliert wird mit `release 25` (Paper 26.x braucht Java 25). Das Jar liegt danach in `build/libs/TextGuard-<version>.jar`.

## Testserver (`testserver/`, nicht in Git)

Paper 26.3 (Build 253) und PacketEvents 2.14.0, Flachwelt, `online-mode=true`, Port 25565, EULA ist gesetzt.

- Starten: `testserver/start.sh` (kopiert das Jar aus `build/libs` nach `testserver/plugins/TextGuard.jar`, Server läuft im Hintergrund)
- Konsolenbefehl: `testserver/befehl.sh "plugins"` (Eingabe über die Named Pipe `befehle.fifo`)
- Log lesen: `tail -f testserver/konsole.log`
- Stoppen: `testserver/stop.sh` (schickt `stop`, wartet aufs Ende)
- Plugin aktualisieren: `./gradlew build`, dann `stop.sh` und `start.sh` (kein Reload, `start.sh` kopiert das neue Jar)

Paper lädt keine Symlinks aus `plugins/`, deshalb wird kopiert.

## Sprache

Code, Kommentare, Doku und Meldungen auf Deutsch (Paper-API bleibt englisch). Commits, PR-Titel und PR-Beschreibung mit ae/oe/ue statt Umlauten, weil sie beim Squash-Merge zum Commit auf main werden. In Markdown-Dateien echte Umlaute.

## Threads

Paketlauscher laufen auf Netty-Threads: dort nie auf Welt oder Bukkit zugreifen, nur unveränderliche Daten lesen.
