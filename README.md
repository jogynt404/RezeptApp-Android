# RezeptApp für Android

Liest Rezepte aus Instagram-, TikTok- und Facebook-Posts aus. Die KI läuft **komplett auf dem Handy**.

## So funktioniert es

1. **Teilen:** In Instagram, TikTok oder Facebook auf *Teilen* und dann auf *RezeptApp* tippen.
2. **Laden:** yt-dlp holt den Beschreibungstext. Steht dort kein Rezept, lädt die App den Ton.
3. **Anhören:** Whisper schreibt mit, was im Video gesagt wird (offline).
4. **Sortieren:** Die Sprach-KI Qwen 2.5 macht daraus Zutaten und Schritte (offline).
5. **Speichern:** Rezepte bleiben auf dem Handy, sind durchsuchbar und lassen sich als Text teilen.

Internet braucht die App nur zum Abrufen des Posts und einmalig zum Laden der Modelle.

## Installation

1. Auf GitHub unter **Releases** die neueste `RezeptApp-v….apk` auf dem Handy herunterladen.
2. Antippen und installieren. Android fragt einmalig, ob der Browser Apps installieren darf. Erlauben.
3. App öffnen, **Einstellungen**, beide Modelle herunterladen (zusammen ca. 2 GB, am besten im WLAN).
4. Bei Bedarf unter *Anmeldung* bei Instagram anmelden.

## Voraussetzungen

- Android 8 oder neuer, 64-Bit (arm64)
- Empfohlen: 8 GB RAM, ca. 3 GB freier Speicher

## Bausteine

| Aufgabe | Bibliothek |
|---|---|
| Post laden | youtubedl-android (yt-dlp mit Python und ffmpeg) |
| Spracherkennung | sherpa-onnx mit Whisper small oder base |
| Sprach-KI | Google LiteRT-LM mit Qwen2.5-1.5B-Instruct |
| Oberfläche | Jetpack Compose |

## Bauen

Jeder Push auf `main` baut über GitHub Actions eine signierte APK und legt sie als Release ab.
Für die gleichbleibende Signatur werden die Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` und `KEY_ALIAS` genutzt.

## Hinweis

Die Nutzungsbedingungen von Meta und TikTok erlauben automatisiertes Abrufen eigentlich nicht.
Die App ist für die private Rezeptsammlung gedacht.
