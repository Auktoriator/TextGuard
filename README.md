# TextGuard

Paper-Plugin für Minecraft Java 26.3, das pro Spielergruppe festlegt, welche Texte (Schilder, Bücher, Mob-Namen) ein Spieler lesen kann.

## Was TextGuard tut

- Ein Text, den ein Spieler nicht lesen darf, wird ihm als Runen angezeigt. Das passiert nur im Netzwerkpaket an diesen Spieler.
- Welt, Schilder, Bücher und Items behalten immer den echten Text. Das Original bleibt also erhalten.
- Jeder Text trägt ein Kennzeichen (z. B. `geheim`). Gruppen legen fest, welche Kennzeichen sie lesen dürfen.

## Voraussetzungen

- Paper 26.3
- Java 25
- PacketEvents 2.14.0 oder neuer (https://modrinth.com/plugin/packetevents)

## Installation

1. Beide Jars in den Ordner `plugins/` legen: PacketEvents und `TextGuard-<version>.jar` (entsteht mit `./gradlew build` in `build/libs/`).
2. Server neu starten.

## Installation aus dem Release

1. `TextGuard-1.1.0.jar` von https://github.com/Auktoriator/TextGuard/releases herunterladen.
2. Die SHA-256-Prüfsumme aus dem Release mit `shasum -a 256 TextGuard-1.1.0.jar` vergleichen.
3. Jar in den Ordner `plugins/` legen (zusammen mit PacketEvents) und den Server neu starten.

## Rechte

- OP Stufe 4 ist automatisch Admin und hat Bypass.
- `textguard.admin`: darf die Befehle benutzen.
- `textguard.bypass`: sieht immer alle Texte unverschleiert.
- `textguard.gruppe.<name>`: Spieler gehört zur Gruppe `<name>`, zusätzlich zu den Mitgliedern, die per Befehl eingetragen sind.

Die Konsole darf alle Befehle benutzen.

## Gruppen, Regeln und Kennzeichen

- **Gruppe**: hat einen Namen (a-z, 0-9, `_` und `-`, höchstens 32 Zeichen) und Mitglieder. Ein Spieler sieht einen Text, wenn eine seiner Gruppen ihn lesen darf.
- **Regel**: gilt pro Art (`schilder`, `buecher`, `mobnamen`). Sie besteht aus einem Grundsatz (`alle` oder `keine` lesbar) und Ausnahmen. Eine Ausnahme kehrt den Grundsatz für Texte mit diesem Kennzeichen um.
- **Kennzeichen**: ein Name, den ein Text trägt. Er wird per Befehl an Schilder, Bücher oder Mob-Namen vergeben.
- **Standard**: Ohne eigene Gruppe gilt die Gruppe `standard`. Sie gibt es immer und sie lässt sich nicht löschen.

Beispiele:

- **alle außer geheim**: Alle lesen die Art, nur Texte mit Kennzeichen `geheim` nicht.
  `/textguard regel standard schilder alle`, dann `/textguard regel standard schilder ausnahme hinzufuegen geheim`
- **keine außer quest1**: Niemand liest die Art, nur Texte mit Kennzeichen `quest1`.
  `/textguard regel <gruppe> schilder keine`, dann `/textguard regel <gruppe> schilder ausnahme hinzufuegen quest1`

## Befehle

Alle Befehle beginnen mit `/textguard` (Alias `/tg`) und brauchen das Admin-Recht.

```
/textguard gruppe anlegen <gruppe>
/textguard gruppe loeschen <gruppe>
/textguard gruppe liste
/textguard gruppe zeigen <gruppe>
/textguard mitglied hinzufuegen <gruppe> <spieler>
/textguard mitglied entfernen <gruppe> <spieler>
/textguard regel <gruppe> <art> alle
/textguard regel <gruppe> <art> keine
/textguard regel <gruppe> <art> ausnahme hinzufuegen <kennzeichen>
/textguard regel <gruppe> <art> ausnahme entfernen <kennzeichen>
/textguard kennzeichen hinzufuegen <kennzeichen> [schild <x y z> | entities <selektor>]
/textguard kennzeichen entfernen <kennzeichen> [schild <x y z> | entities <selektor>]
/textguard kennzeichen zeigen [schild <x y z> | entity <selektor>]
/textguard ansicht <gruppe>
/textguard ansicht aus
/textguard werkzeug [kennzeichen]
/textguard neuladen
```

- `<art>`: `schilder`, `buecher` oder `mobnamen`.
- `gruppe zeigen`: zeigt Mitglieder, Spieler mit Gruppenrecht (online) und die Regeln.
- `kennzeichen`: Ohne Ziel gilt, was du benutzt oder anschaust. Zuerst zählt ein Buch in der Haupthand, danach eine Entity bis 6 Blöcke entfernt (keine Spieler), danach ein Schild bis 6 Blöcke entfernt. `schild <x y z>` nimmt ein bestimmtes Schild (auch von der Konsole), `entities <selektor>` mehrere Mobs (Spieler werden übersprungen), `entity <selektor>` einen Mob.
- `ansicht <gruppe>`: Du siehst die Welt so, wie die Gruppe sie sieht. Nur für Spieler. `ansicht aus` beendet die Testansicht.
- `werkzeug [kennzeichen]`: Gibt dir das TextGuard-Werkzeug (siehe unten). Hältst du es schon, wird nur das Kennzeichen gewählt.
- `neuladen`: Lädt die Gruppen neu. Schlägt das fehl, bleiben die alten Regeln.

## Werkzeug

Mit dem Werkzeug setzt du Kennzeichen an Schildern und Mobs per Klick, ähnlich wie mit dem Debug-Stick. Es ist eine Diamanthacke namens „TextGuard-Werkzeug“, die du mit `/textguard werkzeug` bekommst. Normale Diamanthacken bleiben normal.

- **Linksklick**: wählt das nächste Kennzeichen. Zur Auswahl stehen alle Kennzeichen, die in einer Regel als Ausnahme vorkommen. Ein anderes wählst du mit `/textguard werkzeug <kennzeichen>`.
- **Rechtsklick auf ein Schild oder einen Mob**: setzt das gewählte Kennzeichen oder entfernt es.
- Die Actionbar zeigt danach, ob das Kennzeichen gesetzt ist (✔) oder nicht (✘), und welche Gruppen das Objekt lesen dürfen.

Mit dem Werkzeug in der Hand öffnet sich kein Schild-Editor, nichts wird abgebaut und kein Mob verletzt. Das gilt nur für Admins, bei allen anderen ist es eine normale Hacke. Bücher bekommen ihr Kennzeichen weiter per Befehl (Buch in die Haupthand, dann `/textguard kennzeichen …`).

## Schutz vor Datenverlust

Wer einen Text nur als Runen sieht, könnte ihn beim Speichern versehentlich überschreiben. Deshalb:

- **Schild**: Der Editor öffnet sich nicht, wenn der Spieler den Text nicht lesen darf. Auch das Absenden wird blockiert.
- **Buch**: Bearbeiten und Signieren sind gesperrt, wenn das Buch Text enthält, den der Spieler nicht lesen darf. Ein leeres Buch bleibt beschreibbar.
- **Creative**: Der Client schickt Items mit Runen zurück. TextGuard stellt dabei das Original wieder her.

## Bekannte Grenzen

- Chat- und Todesmeldungen werden nicht verschleiert.
- Hover-Events (Tooltips) werden nicht verschleiert.
- Bossleisten werden nicht verschleiert.
- Lore und Itemnamen werden nicht verschleiert.
- Die Länge eines Textes bleibt erkennbar.
- Ersetzte Schilder: https://github.com/Auktoriator/TextGuard/issues/10

## Stand

Version 1.1.0. Version 1.0.0 wurde am 17.11.2026 mit echtem Client auf Paper 26.3 geprüft, das Werkzeug noch nicht: https://github.com/Auktoriator/TextGuard/issues/17

## Lizenz

GPL-3.0
