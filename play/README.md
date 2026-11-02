# Play Store

Grafiken: `.venv/Scripts/python play/make_graphics.py` → `play/out/icon_512.png`, `play/out/feature_1024x500.png`.

Screenshots: `.venv/Scripts/python play/make_screenshots.py` → `play/out/screens/de/` und `play/out/screens/en/`
(je 6 Bilder, 1080×1920 mit Überschrift; Play erlaubt höchstens 2:1, rohe 1080×2400-Aufnahmen sind zu lang).
Die Rohaufnahmen liegen in `play/raw/` (`raw_<de|en>_<home|rules|feed|group|ranking|profile>.png`): aus dem Emulator
mit einer Demo-Welt (6 Spieler, Posts, Gruppenchat „Morgenclub“), Statusleiste im Demo-Modus (16:45, voller Akku).
In der Play Console: Store-Eintrag → Telefon-Screenshots, DE-Bilder für Deutsch, EN-Bilder für Englisch.

AAB bauen: `cd android && ./gradlew.bat :app:bundleRelease -Pplay -PmomentumServer=https://momentum.mal-zeta.de`
→ `android/app/build/outputs/bundle/release/app-release.aab`

## Store-Eintrag

**Name:** Momentum

**Kurzbeschreibung (DE, ≤ 80):** Gewohnheiten als Spiel: sammle Momentum, vom Fahrrad bis ins Raumschiff.

**Short description (EN, ≤ 80):** Habits as a game: build momentum, from a bicycle to a spaceship.

**Beschreibung (DE):**

Momentum macht aus deinen Gewohnheiten ein Rennen. Alles, was dich voranbringt – Sport, Lesen, Lernen, früh
aufstehen – ist ein Beschleuniger: ein Tap, und dein Momentum steigt. Was dich ausbremst – Doomscrolling, Snooze,
Fast Food – ist ein Bremser. Jeden Tag zieht ein kleiner Verfall am Momentum, also bleib dran.

• Eigene Regeln: Beschleuniger und Bremser mit festen, wachsenden oder abnehmenden Werten
• Fahrzeuge als Meilensteine: vom Fahrrad über Flugzeuge bis in den Hyperraum
• Faires Tageslimit, das sich an alle Spieler anpasst
• Freunde, Feed, Stories, Beiträge mit Fotos und Videos
• Ende-zu-Ende-verschlüsselte Chats und Gruppen
• Gruppen-Challenges und Ranglisten (heute, Freunde, Gruppen)
• Widgets für den Startbildschirm
• Retro-Pixel-Look, Deutsch und Englisch

**Full description (EN):**

Momentum turns your habits into a race. Everything that moves you forward – sport, reading, learning, getting up
early – is an accelerator: one tap and your momentum rises. Whatever slows you down – doomscrolling, snoozing, junk
food – is a decelerator. A small daily decay pulls at your momentum, so keep going.

• Your own rules: accelerators and decelerators with fixed, growing or shrinking values
• Vehicles as milestones: from a bicycle via planes into hyperspace
• A fair daily limit that adapts to all players
• Friends, feed, stories, posts with photos and videos
• End-to-end encrypted chats and groups
• Group challenges and leaderboards (today, friends, groups)
• Home-screen widgets
• Retro pixel look, English and German

**Kategorie:** Gesundheit & Fitness (oder Lifestyle) · **Kontakt-E-Mail:** Pflicht · **Website:** https://momentum.mal-zeta.de
**Datenschutzerklärung:** https://momentum.mal-zeta.de/privacy

## App-Inhalte (Play Console → Richtlinie → App-Inhalte)

- **Datenschutzerklärung:** URL oben.
- **App-Zugriff:** Zugangsdaten eines Testkontos für die Prüfer angeben (E-Mail + Passwort), alle Funktionen frei.
- **Werbung:** keine.
- **Einstufung (IARC):** soziale Funktionen / Nutzer kommunizieren miteinander, nutzergenerierte Inhalte; keine Gewalt usw.
- **Zielgruppe:** 16+ (nicht für Kinder; nichts an Kinder gerichtet).
- **Datensicherheit:**
  - Erhoben: E-Mail-Adresse, Nutzer-IDs (Konto), Name (Benutzername), Fotos, Videos, Audio (Sprachnachrichten),
    Nachrichten (In-App), sonstige nutzergenerierte Inhalte, App-Interaktionen (Taps/Verlauf), Absturz-/Diagnose: nein,
    Geräte-IDs: Push-Token.
  - Zweck: App-Funktionen, Kontoverwaltung, Sicherheit/Betrugsprävention. Keine Weitergabe an Dritte zu deren Zwecken,
    keine Werbung, kein Tracking.
  - Verschlüsselung bei der Übertragung: ja (HTTPS). Löschung: ja, in der App und unter /delete-account.
- **Kontolöschung:** URL https://momentum.mal-zeta.de/delete-account
- **Berechtigungen:** Kamera und Mikrofon nur für Fotos/Videos/Sprachnachrichten, die der Nutzer selbst aufnimmt.

## Google Cloud (für „Mit Google fortfahren“)

Im Projekt `momentum-df519` (console.cloud.google.com → APIs & Dienste):
1. OAuth-Zustimmungsbildschirm: extern, App-Name Momentum, Support-E-Mail, Logo optional, Datenschutz-URL, Bereiche
   nur `openid` und `email` (keine Prüfung durch Google nötig), auf „In Produktion“ stellen.
2. Anmeldedaten → OAuth-Client-ID **Webanwendung**: Weiterleitungs-URI
   `https://momentum.mal-zeta.de/api/auth/google/callback`. Client-ID + Secret in `/opt/momentum/.env`:
   `GOOGLE_CLIENT_ID=…`, `GOOGLE_CLIENT_SECRET=…`, dann `docker compose up -d`.
3. OAuth-Client-ID **Android** (je Signaturschlüssel eine): Paket `com.momentum.app` + SHA-1 von
   - Debug-Key / Website-APK: `64:18:42:14:EE:28:36:1C:D3:DA:9C:E7:35:4E:D1:10:1E:07:2D:0D`
   - Upload-Key: `94:A3:34:06:A1:80:C8:22:8F:8E:6E:69:1E:98:3B:BA:4B:55:7E:D5`
   - Play-App-Signaturschlüssel: steht nach dem ersten Upload in der Play Console unter „App-Integrität“.
   Die Android-Clients werden nirgends eingetragen; Google erkennt die App an Paket + Signatur.

Betreiber für die Rechtsseiten: `MOMENTUM_OPERATOR=Vorname Name\nStraße 1\n12345 Ort\nE-Mail: …` in derselben `.env`.
