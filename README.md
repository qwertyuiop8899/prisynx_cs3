# PriSynx Suite

Suite di plugin per **Nuvio** e **NuvioTV** per lo streaming in lingua italiana e internazionale, con supporto ad auto-delay audio, multi-qualità (fino a 4K) e sorgenti dirette.

---

## 📺 Provider Disponibili (14 Sorgenti)

| Icona | Provider | Contenuti | Risoluzione & Formato | Audio / Sottotitoli | Note & Caratteristiche |
| :---: | :--- | :--- | :--- | :--- | :--- |
| ⚡ | **DualSync** | Film & Serie TV | 4K / 1080p · HLS | 🇮🇹 Audio ITA + 🌐 Originale | Video Cinejoy/Vidfast con mux audio ITA e AutoSync delay |
| 🎬 | **MovyITA** | Film & Serie TV | 1080p · HLS | 🇮🇹 Audio ITA + 🌐 Originale | Flusso Movy con doppia traccia audio sincronizzata |
| 🏰 | **Altadefinizione** | Film & Serie TV | 1080p / 720p · MP4 | 🇮🇹 Audio ITA | CDN MP4 diretta ad alta velocità |
| 🎞️ | **Guardoserie** | Serie TV | 1080p / 720p · HLS / MP4 | 🇮🇹 Audio ITA + Sub-ITA | Player Uqload, Abyss (mloadm) e Loadm |
| 📺 | **CB01** | Film & Serie TV | 1080p / 720p · MP4 / HLS | 🇮🇹 Audio ITA + Sub-ITA | Risoluzione dinamica probe MP4 su MixDrop |
| 🍿 | **Eurostreaming** | Serie TV | 720p / 1080p · HLS / MP4 | 🇮🇹 Audio ITA + Sub-ITA | MixDrop, Turbovid, DeltaBit con captcha bypass automatico |
| 💎 | **VixSrc** | Film & Serie TV | 1080p / 720p · HLS | 🇮🇹 Audio ITA + Multi-audio + Sub | Streaming HLS con selezione tracce audio e sub |
| 🌐 | **PaCC** | Film & Serie TV | 1080p / 720p · HLS | 🇮🇹 Audio ITA + Sub-ITA | Multi-server HLS con traccia audio italiana |
| 🎨 | **Toonitalia** | Cartoni & Serie TV | 1080p / 720p · HLS / MP4 | 🇮🇹 Audio ITA + Sub-ITA | Cartoni animati, serie vintage e anime (VOE, Uqload) |
| 🍰 | **Real Time** | Canale TV / Reality | 1080p · HLS | 🇮🇹 Audio ITA | Programmi Discovery Italia ufficiali (solo stagioni online) |
| 🏛️ | **RaiPlay** | Fiction, Film & Doc | 1080p · HLS | 🇮🇹 Audio ITA | Catalogo RaiPlay ufficiale in alta definizione (DRM-free) |
| 🌸 | **AnimeWorld** | Anime | 1080p / 720p · MP4 | 🇮🇹 Doppiato + 🇯🇵 SUB-ITA | Link MP4 diretti veloci con mapping automatico |
| 🦊 | **AnimeSaturn** | Anime | 1080p / 720p · HLS / MP4 | 🇮🇹 Doppiato + 🇯🇵 SUB-ITA | StreamPeaker HLS/MP4 con mapping automatico |
| 🏮 | **AnimeUnity** | Anime | 1080p / 720p · HLS | 🇮🇹 Doppiato + 🇯🇵 SUB-ITA | Server VixCloud con bitrate elevato (ITA e SUB) |
| ▶️ | **VidxGo** | Film & Serie TV | 4K / 1080p · HLS | 🌐 Originale / Multi | Solo CloudStream · rinnovo automatico token |
| 🏴‍☠️ | **IlCorsaroViola** | Film & Serie TV | 4K / 1080p · Torrent / Debrid | 🇮🇹 Audio ITA + Multi | Torrent P2P con seeders, formati HEVC/H.264 e bitrate calcolato; con Real-Debrid, AllDebrid o TorBox i torrent in cache diventano link diretti |

---

## 🎯 Audio Delay Automatico & Manuale

Molti video 4K/FHD e audio ITA provengono da edizioni diverse (es. loghi iniziali o sigle di durata differente). PriSynx include l'offset millimetrico per allinearli.

### 📺 NuvioTV (Android TV / Google TV)
I plugin includono nei metadati dello stream:
```javascript
behaviorHints: {
  audioDelayMs: 1250 // Ritardo in millisecondi (fino a ±60s)
}
```
* **Con la PR [#3781](https://github.com/NuvioMedia/NuvioTV/pull/3781)**: NuvioTV applica il ritardo **automaticamente all'avvio**, senza premere alcun tasto sul telecomando.
* **Supporto NuvioTV**: ExoPlayer e mpv su Android TV supportano l'impostazione del delay audio.

### 📱 Impostazione Manuale (Nuvio Mobile / Finché la PR è in review)
1. Nella lista stream, leggi il valore nel badge (es. `⏱️ +1,25 s`).
2. Avvia la riproduzione.
3. Apri le opzioni audio del player (icona ⚙️ / altoparlante).
4. Imposta manualmente il ritardo indicato.

---

## ⚙️ Come Funziona l'Auto-Misurazione

```mermaid
flowchart TD
    A["Click su Film o Episodio in Nuvio"] --> B["Controllo Offset su ToastFlix"]
    B -->|Trovato| C["Stream con Audio in Sync"]
    B -->|Non Trovato| D["Riproduzione Subito Pronta + Job in Background"]
    D --> E["Coda AutoSync (Deduplicazione MediaKey)"]
    E --> F{"Slot Worker Liberi?"}
    F -->|Disponibile - Max 2| G["Elaborazione Audio & Correlazione"]
    F -->|In Uso| H["In attesa nella coda"]
    G --> I["Salvataggio Offset su ToastFlix dual.db"]
    I --> J["Al prossimo click: Sincronizzato"]
```

### Regole di Funzionamento:
* **Trigger al click**: la misurazione **non parte durante la ricerca** nel catalogo, ma solo al click sul singolo episodio (quando vengono risolti gli stream reali).
* **Nessuna attesa**: il video è visibile e riproducibile subito; il calcolo avviene asincrono in background.
* **Coda (Max 2 Worker contemporanei)**: per non sovraccaricare la CPU, AutoSync gestisce massimo 2 elaborazioni parallele. Eventuali altri titoli attendono in coda.
* **Deduplicazione**: richieste multiple dello stesso episodio incrementano le priorità senza duplicare i calcoli.
* **Retry intelligente**: se un titolo era incompatibile nel vecchio database ToastFlix (es. vecchio fastpass), il plugin lo reinvia ad AutoSync per verificarlo con precisione a traccia intera.

---

## 🏷️ Significato dei Badge

| Badge | Significato | Azione |
| :--- | :--- | :--- |
| `⏱️ +X,XX s` | **Offset misurato** | Su NuvioTV si applica da solo; su mobile imposta il valore a mano. |
| `🔊✅ in sync` | **In perfetto sincrono** | Nessun ritardo necessario (< 100 ms). |
| `⏳ da misurare` | **Primo avvio** | Puoi guardare il video subito; calcolo avviato in background. |
| `⏳ in coda` | **In coda** | Coda momentaneamente piena (2 worker attivi); calcolo a breve. |
| `⚙️ in calcolo` | **Calcolo in corso** | Analisi attiva in questo momento (richiede 60–90s). Riapri tra poco. |
| `⛔ versioni diverse` | **Incompatibile** | Montaggi differenti o tagli interni verificati da AutoSync. |

---

## 📦 Installazione in Nuvio

Aggiungi i manifest nella sezione **Plugin** di Nuvio o NuvioTV:

### PriSynx (17 provider, incluso Il Corsaro Viola)
```text
https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/nuvio/manifest.json
```

---

## 🤖 CloudStream (estensioni native)

Questo repository ospita anche le estensioni native **CloudStream** (Kotlin `.cs3`) che portano i provider del repository Nuvio/Stremio su CloudStream. I plugin Nuvio/Stremio (JavaScript) non sono caricabili da CloudStream: per questo ogni provider è stato riscritto come `MainAPI` Kotlin e pubblicato nel branch `builds`.

### Installazione in CloudStream

In CloudStream: **Impostazioni → Estensioni → Aggiungi repository** e incolla **uno** di questi URL:

| Repository | URL | Cosa contiene |
| :--- | :--- | :--- |
| 🧩 **PriSynx Center** (consigliato) | `https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/center.json` | L'estensione **PriSynx** con tutti i provider, **RaiPlay** e **KissKH integrati**, più il menu ⚙️ per TV; RaiPlay e KissKH sono installabili anche da soli |
| 🔌 **Provider singoli** | `https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/singoli.json` | Un'estensione per provider, attivabili/aggiornabili una per una (niente menu) |

Il vecchio URL `.../main/main.json` resta valido e mostra tutte le estensioni insieme.
Non installare insieme PriSynx e i provider singoli: avresti i link doppi.

Provider singoli disponibili: `Altadefinizione`, `AnimeSaturn`, `AnimeUnity`, `AnimeWorld`, `CB01`, `IlCorsaroViola`, `DualSync`, `Eurostreaming`, `Filmez`, `Guardoserie`, `KissKH`, `MovyITA`, `OnlineSerieTV`, `PaCC`, `RaiPlay`, `RealTime`, `Toonitalia`, `VidxGo`, `VixSrc`.

**KissKH** include un catalogo nativo (drama e film coreani, giapponesi e thailandesi, più anime) e apre schede/episodi usando gli ID KissKH: non serve un ID TMDB/IMDb per navigare il catalogo.

### 🧩 PriSynx (hub con menu)

L'estensione **PriSynx** raccoglie in un'unica sorgente tutti i provider, **RaiPlay** e **KissKH**: al click su un titolo TMDB interroga in parallelo le sorgenti attive e invia i link nell'ordine scelto; le righe e i risultati RaiPlay (film, serie, documentari, dirette) si aprono e si riproducono direttamente dal catalogo Rai, mentre KissKH usa il proprio catalogo nativo (drama e film asiatici).
Dal pulsante ⚙️ dell'estensione (*Impostazioni → Estensioni → PriSynx*) si apre il menu a schermo intero, pensato per il telecomando (frecce + OK, *Indietro* per chiudere; ogni modifica è salvata subito, ricarica la home per vederla):

| Scheda | Cosa si può fare |
| :--- | :--- |
| **Provider** | OK su una riga = ON/OFF; ▲/▼ a destra per l'ordine dei link; *Attiva tutti* / *Disattiva tutti* |
| **Home** | Ordine delle sezioni (TopX, TMDB, Anime Kitsu, RaiPlay, KissKH) e righe TMDB: Tendenze, Film/Serie popolari, Film/Serie più votati (attive di default), Al cinema, In arrivo, Serie in onda |
| **TopX** | Mostrare/nascondere TopX, **stile Portrait o Landscape** (copertine fresh verticali o orizzontali, con un manifest per stile), cambiare/ripristinare l'URL del manifest dello stile attivo, mostrare/nascondere i singoli cataloghi e **ordinarli** con ▲ ▼ |
| **Anime** | Anime dal catalogo **Kitsu** (righe in home: tendenza, in corso, più popolari, più votati; risultati nella ricerca) e opzione per nascondere gli anime giapponesi da TMDB |
| **RaiPlay** | Righe RaiPlay in home e risultati RaiPlay nella ricerca (ON/OFF), scelta delle singole righe RaiPlay |
| **KissKH** | Righe KissKH in home e risultati KissKH nella ricerca (ON/OFF), scelta dei cataloghi (drama/film coreani, giapponesi, thailandesi, anime) e impostazioni KissKH (URL, chiave DeepL, lingua di traduzione, filtro adulti) |
| **Debrid** | Sblocco dei magnet di **IlCorsaroViola** in link diretti con **Real-Debrid**, **AllDebrid** o **TorBox**: scelta del servizio, chiave API (con verifica dell'account), ON/OFF e **attesa massima** del download (0/20/40/60/90/120 s) |
| **Altro** | **Sincronizzazione Cloud E2EE (PriSynx Vault)** con PIN a 6 cifre, numero massimo di torrent IlCorsaroViola, **Movy/DualSync: solo link sincronizzati**, ripristino impostazioni |

**KissKH nel hub**: le righe native compaiono in home (se attive) e i risultati nella ricerca; aprendo una scheda, episodi e stream vengono risolti da KissKH. La voce *Provider* include KissKH come sorgente per i titoli TMDB: si può disattivare o spostare nell'ordine come le altre.

**KissKH e relay Cloudflare (opzionale)**: KissKH è protetto da Cloudflare e da alcune reti le richieste ricevono una *challenge* (`403` con `cf-mitigated: challenge`), quindi catalogo vuoto e niente riproduzione. Se succede solo a te e non ad altri utenti, il problema è la reputazione del tuo IP, non il plugin. Dal campo **Relay Cloudflare** nelle impostazioni KissKH si può indicare un relay fidato che esegua le richieste al posto dell'app:

```
https://mio-relay.example/fetch?token=SEGRETO&url={url}
```

`{url}` viene sostituito con l'URL già codificato: va lasciato così com'è. Il relay deve esporre almeno:

| Endpoint | Uso |
|---|---|
| `GET /fetch?url=…` | restituisce il body originale della URL richiesta |
| `POST /store` | riceve un sottotitolo generato (decifrato o tradotto) e risponde `{"url": …}` |
| `GET /sub/<id>.srt` | serve quel sottotitolo al player |

Con solo `/fetch` funzionano catalogo, schede e i sottotitoli `.srt`/`.vtt`; senza `/store` i sottotitoli legacy cifrati e quelli tradotti da DeepL restano sul server locale del dispositivo. Non esiste un servizio ufficiale: nella cartella **`tools/kisskh-relay/`** c'è un'implementazione di riferimento (FastAPI + Docker, con cookie e token vuoti), da usare solo se serve. Senza relay il plugin funziona come sempre, con richieste dirette.

**Debrid (IlCorsaroViola)**: dalla scheda *Debrid* scegli il servizio (**Real-Debrid**, **AllDebrid** o **TorBox**), incolla la chiave API (c'è anche *Apri pagina delle chiavi* e *Verifica chiave* per controllare l'account) e regola l'**attesa massima**. I torrent già in cache diventano subito link diretti con il tag del servizio (`· RD`, `· AD`, `· TB`); gli altri vengono inviati al servizio (il magnet resta, con l'etichetta `· magnet (RD in download)`) e al tentativo successivo, quando il download è pronto, arriva il link diretto senza riaggiungere il torrent. La stessa schermata è disponibile come impostazioni dell'estensione singola IlCorsaroViola (ingranaggio in *Estensioni*).

Locandine: le card TMDB e Kitsu mostrano il voto (TMDB / IMDb) se la versione di CloudStream lo supporta. Aprendo un titolo da una riga **TopX**, lo sfondo della pagina è la copertina TopX landscape; dalle righe TMDB resta lo sfondo TMDB.

**Anime con Kitsu**: TMDB raggruppa gli anime in stagioni/episodi diversi da quelli dei siti anime (es. *Jujutsu Kaisen* S2E1 diventava l'episodio 25), quindi molti episodi non si trovavano. Kitsu ha una scheda per ogni stagione/cour con la stessa numerazione di AnimeUnity, AnimeSaturn e AnimeWorld: aprendo un anime dalle righe o dalla ricerca Kitsu, PriSynx interroga solo queste tre sorgenti (nell'ordine scelto) con l'ID Kitsu. Le pagine mostrano poster, sfondo, logo, trama in italiano (da TMDB, se c'è), trailer, titoli e miniature degli episodi, e i titoli collegati (sequel/prequel). Di default gli anime giapponesi non compaiono più nei cataloghi e nella ricerca TMDB (si può riattivare dalla scheda *Anime*). Anche il percorso TMDB è più tollerante: se l'episodio mappato non esiste, viene riprovato col numero originale.

Le estensioni singole restano installabili a parte (ognuna con home e ricerca proprie).

### 🔒 Sincronizzazione Cloud Crittografata (PriSynx Vault E2EE)

Dal menu **PriSynx → Impostazioni → Altro** è disponibile il sistema di sincronizzazione e backup multipiattaforma (Zero-Knowledge E2EE):

- **Cosa sincronizza**:
  - Preferenze di CloudStream (temi, player, sottotitoli, repository installati).
  - Impostazioni di **tutti i plugin** presenti (chiavi Real-Debrid, AllDebrid, TorBox, preferenze KissKH, categorie, ecc.).
  - Cronologia e avanzamento riproduzione al secondo (`ResumeWatching`), stagione ed episodio visti, preferiti e segnalibri.
- **Come funziona**:
  1. **Dispositivo A (Sorgente)**: Apri *Impostazioni → Altro*, clicca su **"🎲 Genera nuovo PIN casuale"** (oppure scegline uno a 6 cifre) e premi **"📤 Cifra e Invia Backup alla VPS"**. Il client cifra tutti i dati in locale con **AES-256-GCM** e li invia al server vault.
  2. **Dispositivo B (Destinazione)**: Apri *Impostazioni → Altro*, inserisci lo stesso PIN a 6 cifre e premi **"📥 Scarica e Ripristina da questo PIN"**. Tutti i dati vengono scaricati, decifrati in locale e applicati istantaneamente.
- **Sicurezza e Privacy**:
  - Nessun dato in chiaro: la VPS riceve solo byte cifrati opachi e non conosce la chiave di decifratura.
  - Protezione anti-bruteforce: dopo 5 tentativi consecutivi di PIN errato, il server blocca l'accesso per 60 minuti.
  - Il codice del server è open source e disponibile nella cartella [`server/`](server/).

---

### 🎧 Movy, Cinejoy e VidFast con audio ITA (MovyITA / DualSync)

| Estensione | Video | Audio italiano |
| :--- | :--- | :--- |
| **MovyITA** | player Movy (file VidFast, server miami/boise/…): 4K / 1080p / 720p | traccia VixSrc |
| **DualSync** | Cinejoy: 4K / 1080p | traccia VixSrc |

Per ogni qualità compaiono due link:

- **`… ITA <badge>`**: video originale + audio italiano VixSrc (e sottotitoli VixSrc) in un unico flusso. Se l'offset è misurato viene **applicato in automatico**: niente da regolare.
- **`… solo video`**: il solo video con l'audio originale, utile se il link ITA non parte o se usi un player esterno.

> **Solo link sincronizzati (default)**: MovyITA e DualSync mostrano solo i link ITA con offset già misurato e applicato (`🔊✅ in sync` o `⏱️ … auto`). Niente `solo video`, niente `da misurare`/`in coda`/`⛔`. Se un titolo non ha ancora la misura, la richiesta parte lo stesso verso AutoSync: riapri più tardi. Nel hub si disattiva da *Impostazioni → Altro*.

Come scegliere:

1. `🔊✅ in sync` o `⏱️ ±X s auto` → avvia questo: l'audio è già allineato.
2. `⏳ da misurare` / `⏳ N° in coda` / `⚙️ in calcolo` / `🔬 analisi approfondita` → l'offset non è ancora noto: la misura è stata chiesta ad AutoSync. Riapri il titolo dopo qualche minuto. Nel frattempo il link ITA parte **senza** correzione.
3. `⛔ tagli` / `⛔ versioni diverse` / `⛔ 23,976↔25 fps` → video e audio sono edizioni diverse: non sincronizzabile, usa `solo video` o un altro provider.
4. `⚠️ ±X s · player esterno` → offset oltre 60 s: usa `solo video` in un player esterno (VLC/MX/Just) impostando a mano il ritardo audio indicato.

Le misure sono **per player**: Movy usa solo le misure Movy/VidFast, DualSync solo quelle Cinejoy (file con durata uguale possono partire in punti diversi).
Positivo = audio italiano ritardato, negativo = anticipato. Se il link ITA non va, prova un altro server/qualità o il `solo video`.

### ▶️ VidxGo

Sorgente basata su IMDb (film e serie) con stream HLS fino a 4K. I token del CDN scadono dopo pochi minuti: l'estensione li rinnova automaticamente durante la riproduzione.

> ⚠️ **Geoblocking**: RaiPlay è disponibile solo da Italia/Europa. Fuori da queste aree il catalogo e i flussi HLS restituiscono errore/403.
>
> I contenuti con DRM (es. alcuni eventi sportivi) non sono supportati e vengono ignorati.

### Funzionalità CloudStream

| Funzione | Dettagli |
| :--- | :--- |
| 🏠 Home e ricerca | Catalogo e ricerca tramite TMDB (titoli italiani); **Real Time / NOVE / DMAX / Food Network** usano il catalogo nativo Discovery Aurora |
| 🎬 Film e serie | URL TMDB reali (`themoviedb.org/movie/ID`, `/tv/ID`) e URL nativi Discovery, risolti al click |
| ▶️ Streaming | HLS/MP4/Magnet tramite `ExtractorLink` nativi |
| 🇮🇹 Audio italiano | VixSrc, PaCC, anime, CB01/Eurostreaming (MaxStream/uprot), RaiPlay |
| ⚡ 4K + audio ITA | MovyITA e DualSync combinano video 4K con la traccia VixSrc in un master HLS servito su loopback locale |
| 🧩 Captcha uprot | Risoluzione automatica del captcha immagini tramite worker (Filmez, OnlineSerieTV, CB01, Eurostreaming) |
| 🏴‍☠️ Torrent | IlCorsaroViola tramite link Magnet; con un servizio debrid configurato (Real-Debrid, AllDebrid, TorBox) i torrent in cache diventano link HTTP diretti e gli altri vengono inviati al servizio per i tentativi successivi |

**Note e limiti**

- **Auto-sync audio su CloudStream** (MovyITA/DualSync): l'offset misurato da ToastFlix/AutoSync viene applicato automaticamente. CloudStream non ha un ritardo audio, quindi la traccia italiana VixSrc passa da un mini-server HTTP di loopback che riscrive i timestamp (PTS/PCR) dei segmenti TS e aggiunge silenzio iniziale; anche i sottotitoli VixSrc vengono traslati. Video e audio sono uniti in un unico master HLS. Il nome del link mostra lo stato: `🔊✅ in sync`, `⏱️ +1,275 s auto` (offset applicato), `⏳ da misurare` / `in coda` / `⚙️ in calcolo` (misurazione richiesta ad AutoSync: riapri il titolo più tardi), `⛔ tagli` / `versioni diverse` (non sincronizzabile), `⚠️ … · player esterno` (offset oltre 60 s). Con *solo link sincronizzati* (default) vengono mostrati solo i link `in sync` o con offset `auto`.
- I provider che usano `clicka.cc` (Eurostreaming/CB01) risolvono la sorgente MaxStream/uprot; i server MixDrop/Turbovid/DeltaBit dietro clicka non sono inclusi.
- Le pagine “cartella” di CB01/Eurostreaming non vengono seguite.
- Alcuni host (Filmez, OnlineSerieTV, CB01, Eurostreaming) possono richiedere il worker captcha e degradare se il servizio non è raggiungibile.

### Sviluppo e build

I moduli sono uno per provider: ogni cartella è un'estensione CloudStream indipendente (package `prisynx.providers`, RaiPlay in `prisynx.raiplay`). Il codice condiviso vive in `shared/`. Il repository segue la struttura ufficiale delle estensioni CloudStream:

```text
main.json                     # manifest repository per l'app
build.gradle.kts              # progetto Gradle root
settings.gradle.kts           # include automatico dei moduli (ogni cartella con build.gradle.kts)
shared/src/main/kotlin/prisynx/providers/
    core/TmdbProvider.kt      # home/search/load TMDB condiviso
    core/Uprot.kt             # captcha uprot + MaxStream
    core/Vix.kt               # master VixSrc e audio ITA
    core/AnimeMapping.kt      # mapping TMDB -> anime
    WpUprotProvider.kt        # pipeline WordPress + uprot (Filmez/OnlineSerieTV)
    Quadruple.kt              # helper condiviso
VixSrc/                       # un modulo = una estensione .cs3
  src/main/kotlin/prisynx/providers/
    VixSrcPlugin.kt           # @CloudstreamPlugin + registrazione del solo provider
    VixSrcProvider.kt         # home, search, load, loadLinks
AnimeSaturn/ ... (idem per ogni provider)
RaiPlay/
PriSynx/                      # hub: copia in build i sorgenti degli altri moduli (senza i *Plugin.kt)
  src/main/kotlin/prisynx/hub/  # PriSynxPlugin, PriSynxProvider, HubSettings(+Dialog), TopX
.github/workflows/cloudstream-build.yml
```

#### Build automatica (GitHub Actions)

Basta modificare i sorgenti su `main` (anche dall'editor web di GitHub) e fare commit:

1. Modifica il provider in `<Provider>/src/main/kotlin/...` oppure il codice comune in `shared/`.
2. **Alza `version`** nel `build.gradle.kts` del modulo modificato, altrimenti CloudStream non propone l'aggiornamento. Se tocchi `shared/` alza la versione dei moduli che usano il codice cambiato; se tocchi un provider alza anche `PriSynx/build.gradle.kts` (l'hub include i sorgenti di tutti i provider).
3. Commit/push su `main` → il workflow **CloudStream build** (tab *Actions*, ~5 minuti) compila e pubblica sul branch `builds`: `*.cs3`, `plugins.json` (tutti), `plugins-center.json` (PriSynx + RaiPlay), `plugins-singoli.json` (provider singoli).
4. In CloudStream: *Impostazioni → Estensioni* → aggiorna.

Il workflow si può avviare anche a mano da *Actions → CloudStream build → Run workflow*. Se fallisce, il log del passo *Build plugins* mostra l'errore Kotlin (`e: file…:riga`).

#### Nuovo provider

Copia una cartella esistente (es. `VixSrc/`), rinomina `*Plugin.kt` / `*Provider.kt` e classi, imposta `version = 1` e la `description` nel `build.gradle.kts`: `settings.gradle.kts` include da solo ogni cartella con `build.gradle.kts`. Per averlo anche nell'hub aggiungilo a `HubSettings.sources` in `PriSynx/src/main/kotlin/prisynx/hub/HubSettings.kt`.

#### Build locale

Richiede **JDK 17** e **Android SDK**:

```bash
./gradlew make makePluginsJson ensureJarCompatibility   # tutti i moduli
./gradlew :MovyITA:make                                 # un solo modulo
```

I file `.cs3` finiscono in `<Modulo>/build/`.

Il workflow GitHub Actions compila a ogni push su `main` e pubblica `*.cs3`, `*.jar` e `plugins.json` sul branch `builds`.
