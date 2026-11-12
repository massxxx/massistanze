# MassiStanze

Musica sincronizzata in tutte le stanze di casa: il PC fa da centro e telefoni, browser e PC suonano insieme.

- **Musica**: carichi gli MP3 (o m4a, wav, flac, ogg…) dal telefono, vengono salvati sul PC. Coda, playlist, casuale, ripeti.
- **Radio online**: cerca tra migliaia di radio (elenco radio-browser.info), più ascoltate in Italia, preferite, indirizzo a mano.
- **Stanze**: ogni dispositivo è una stanza con nome, volume, acceso/spento e ritardo (per le casse Bluetooth). Prova sincronia con un bip ogni secondo.
- **Annunci**: parli dal microfono (app Android o pagina del PC) e ti sentono tutte le stanze, con la musica abbassata.
- **Chat**: messaggi tra tutti i dispositivi collegati.

Spotify e YouTube non si possono usare: il loro audio è protetto.

## Scarica
- PC Windows: [MassiStanze.exe](https://github.com/massxxx/massistanze/releases/latest/download/MassiStanze.exe)
- Android: [MassiStanze.apk](https://github.com/massxxx/massistanze/releases/latest/download/MassiStanze.apk)
- Qualsiasi telefono (anche iPhone): apri nel browser l'indirizzo che MassiStanze mostra sul PC (porta 8766).

## Come funziona
Il PC decodifica l'audio (PyAV/FFmpeg) e lo manda a pezzi di 100 ms con l'ora esatta in cui suonarli.
Ogni dispositivo allinea il suo orologio a quello del PC e programma i pezzi con Web Audio, correggendo da solo la deriva.

`pc/massistanze.py` (programma del PC) · `web/` (pagina per tutti) · `.github/workflows/app.yml` (exe + APK a ogni modifica).
