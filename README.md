# TermVault

Client SSH nativo per Android con terminale integrato (motore Termux) e
integrazione **Keepass2Android** per credenziali e chiavi private: niente
segreti su disco, tutto arriva dal database KeePass al momento della
connessione.

- Kotlin, minSdk 26, targetSdk 35, Jetpack Compose (Material 3)
- SSH: [sshj](https://github.com/hierynomus/sshj) 0.40.0 (ed25519,
  rsa-sha2-256/512, chacha20-poly1305, curve25519-sha256)
- Terminale: `terminal-emulator` + `terminal-view` di
  [termux-app](https://github.com/termux/termux-app) v0.118.3 (Apache-2.0),
  vendorizzati nei moduli `:terminal-emulator` e `:terminal-view`
- Persistenza: Room · DI: Hilt · Segreti locali: EncryptedSharedPreferences
  (MasterKey AES256-GCM nel Keystore, hardware-backed dove disponibile)

## Build

```bash
./gradlew assembleDebug
```

Nessun passaggio manuale in Android Studio. Prerequisiti:

- Android SDK con platform `android-36` (compileSdk 36, targetSdk 35)
- JDK 17–21 (AGP 8.13 non supporta Java 22+). Se il tuo `JAVA_HOME` è più
  recente, indica a Gradle un JDK compatibile senza versionarlo nel repo,
  es. `./gradlew assembleDebug -Dorg.gradle.java.home=/percorso/jdk-21`.
- `local.properties` con `sdk.dir`: crealo puntando al tuo Android SDK
  (non è versionato). Android Studio lo genera in automatico all'apertura.

I test unitari (mappatura campi KP2A, known_hosts, parsing chiavi):

```bash
./gradlew :app:testDebugUnitTest
```

## Modulo SDK Keepass2Android

`:kp2a-sdk` contiene i sorgenti di
[`Keepass2AndroidPluginSDK2`](https://github.com/PhilippC/keepass2android/tree/master/src/java/Keepass2AndroidPluginSDK2)
(package `keepass2android.pluginsdk`, GPL-3.0, © Philipp Crocoll), importati
come libreria locale del progetto con un `build.gradle.kts` moderno ma
**sorgenti non modificati**. Per aggiornarli:

```bash
git clone --depth 1 --filter=blob:none --sparse https://github.com/PhilippC/keepass2android.git
cd keepass2android && git sparse-checkout set src/java/Keepass2AndroidPluginSDK2
# copiare app/src/main/java/keepass2android/pluginsdk/*.java in kp2a-sdk/src/main/java/keepass2android/pluginsdk/
```

Lato app il protocollo plugin è implementato da:

- `kp2a/Kp2aAccessReceiver.kt` — estende `PluginAccessBroadcastReceiver` con
  scope `SCOPE_QUERY_CREDENTIALS` + `SCOPE_DATABASE_ACTIONS`; nel manifest è
  `exported="true"` con le action `keepass2android.ACTION_TRIGGER_REQUEST_ACCESS`,
  `ACTION_RECEIVE_ACCESS`, `ACTION_REVOKE_ACCESS`.
- `strings.xml` — `kp2aplugin_title`, `kp2aplugin_shortdesc`,
  `kp2aplugin_author` (mostrati nella lista plugin di KP2A).
- `kp2a/Kp2aFields.kt` — mappatura campi entry ↔ credenziali SSH.

**Primo utilizzo**: aprire Keepass2Android → Impostazioni → Plugin →
abilitare "TermVault SSH". Senza questo consenso KP2A rifiuta le query.

## Struttura attesa dell'entry KeePass

| Campo | Uso |
|---|---|
| `URL` | Deve corrispondere alla query dell'host (es. `ssh://web01`). |
| `UserName` | Username SSH. |
| `Password` | Password di login **oppure**, se presente `SSH-PrivateKey`, passphrase della chiave. |
| `SSH-PrivateKey` (custom, **protetto**, multilinea) | Chiave privata in formato OpenSSH/PEM. L'SDK KP2A scambia solo stringhe: la chiave va nel campo custom, **non** come allegato binario. |
| `SSH-Port` (custom, opzionale) | Sovrascrive la porta configurata nell'host. |

Nell'host TermVault si imposta la "Query KP2A" (es. `ssh://web01`); alla
connessione l'app lancia `Kp2aControl.getQueryEntryIntent(query)` e legge i
campi con `getEntryFieldsFromIntent()`. Se il database è bloccato è KP2A
stesso a chiedere lo sblocco. Il pulsante "Crea entry in Keepass2Android"
dell'editor host precompila una entry (via `getAddEntryIntent`) con
`URL = query` e i campi `Password`/`SSH-PrivateKey` marcati protetti.

### Modalità di autenticazione per host

1. **Keepass2Android** — tutto dall'entry KeePass (vedi sopra).
2. **Chiave locale** — ed25519 generata in-app; la chiave privata sta in
   `EncryptedSharedPreferences` (master key nel Keystore) e non lascia mai il
   dispositivo. Con l'opzione "Passphrase da KP2A" il blob è cifrato una
   seconda volta con AES-GCM derivata dalla passphrase (PBKDF2-HMAC-SHA256,
   120k iterazioni) presa dal campo `Password` dell'entry: senza KP2A la
   chiave è inutilizzabile. La chiave pubblica si esporta dal menu della
   sessione (icona chiave → riga `authorized_keys`).
3. **Password** — chiesta a ogni connessione, mai salvata.

## Funzionalità

- Lista host con tag, colore, jump host (un livello) e modalità di autenticazione
- Sessioni a schede; foreground service (`specialUse`, sotto-tipo dichiarato
  nel manifest) tiene vive le connessioni in background
- Tastiera ausiliaria: CTRL/ALT/FN sticky, ESC, TAB, frecce, PgUp/PgDn,
  Home/End, FN+1..0 → F1..F10, incolla
- Port forwarding locale (`-L`) e remoto (`-R`) per host, start/stop dal
  pannello ⇄ della sessione
- Snippet inviabili con un tap dal bottom sheet della sessione
- Browser file SFTP (icona 📁 nella sessione): navigazione directory remote,
  download col selettore di sistema, upload nella directory corrente, con
  barra di avanzamento — sulla stessa connessione (credenziali KP2A e jump
  host inclusi)
- known_hosts persistente: prompt con fingerprint SHA-256 alla prima
  connessione, **allarme evidente** su mismatch (la sostituzione richiede
  conferma esplicita)
- Riconnessione automatica con backoff esponenziale (1→30 s)
- Copia (selezione long-press), incolla, ricerca nello scrollback,
  dimensione font (pulsanti e pinch-to-zoom)

## Note di sicurezza

- Le credenziali ottenute da KP2A vivono **solo in memoria** per la durata
  della sessione (servono alla riconnessione automatica); `char[]` azzerati
  con `wipe()` alla chiusura della scheda. Mai scritte su disco, mai loggate.
- `FLAG_SECURE` su tutta l'app: niente screenshot né anteprime nel task
  switcher.
- Backup disabilitato (`allowBackup=false` + `dataExtractionRules` che
  escludono tutto da cloud backup e device transfer).
- La verifica della chiave host blocca la connessione finché l'utente non
  conferma il fingerprint; su mismatch la sostituzione è un'azione separata e
  marcata come rischiosa.
- Il modulo `terminal-emulator` è vendorizzato con una patch minima
  ("stream mode", cercare `PATCH TermVault` in `TerminalSession.java`): la
  sessione terminale è alimentata dagli stream SSH e non esegue mai processi
  locali né codice JNI.
- Licenze: sorgenti Termux Apache-2.0; Keepass2AndroidPluginSDK2 GPL-3.0;
  sshj Apache-2.0; SQLCipher (opzionale) BSD-style.

## Struttura del progetto

```
app/                    applicazione (Compose, Hilt, Room, sshj)
  ssh/                  connessioni: SshTerminalTab, manager, known_hosts
  kp2a/                 integrazione Keepass2Android
  crypto/               chiavi locali ed25519 (Keystore)
  service/              foreground service sessioni
  ui/                   schermate (hosts, session, snippets)
kp2a-sdk/               Keepass2AndroidPluginSDK2 (sorgenti upstream)
terminal-emulator/      termux terminal-emulator v0.118.3 + patch stream mode
terminal-view/          termux terminal-view v0.118.3 (invariato)
```

## Licenza

TermVault è distribuito sotto **GNU GPL-3.0-or-later** (vedi [LICENSE](LICENSE)).
Il progetto incorpora `Keepass2AndroidPluginSDK2`, che è GPL-3.0: di
conseguenza l'opera nel suo insieme è licenziata GPL-3.0.

Componenti di terze parti (vedi [NOTICE](NOTICE)):

- **Keepass2AndroidPluginSDK2** — © Philipp Crocoll — GPL-3.0
- **termux-app** (`terminal-emulator`, `terminal-view`) — © Termux — Apache-2.0
- **sshj** — Apache-2.0 · **BouncyCastle** — MIT-style

Questo è un progetto personale, non affiliato né a Termux né a Keepass2Android.
