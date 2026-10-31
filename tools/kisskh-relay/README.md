# kisskh-relay

Relay HTTP **opzionale** per il plugin [KissKH](../..) di CloudStream: permette di
superare la challenge Cloudflare (`403` + pagina "Just a moment…") che colpisce
alcuni IP.

Non serve a tutti. Se KissKH funziona normalmente per te, ignora questa cartella:
il plugin non richiede il relay e senza configurazione fa richieste dirette.

Non e un servizio ufficiale KissKH ne di CloudStream e non garantisce l'accesso:
e solo un ponte tra il plugin e il sito.

## Perche serve

Un fingerprint TLS da browser non basta: quando Cloudflare ha deciso di sfidare un
IP, *qualsiasi* client non-browser riceve `403`, perche il challenge va **eseguito**
(JavaScript) per ottenere il cookie `cf_clearance`. Il relay usa due strade:

1. **fast path** — richiesta con fingerprint Chrome (`curl_cffi`) e cookie
   `cf_clearance` gia ottenuto: millisecondi, usata per la maggior parte delle
   chiamate;
2. **solve path** — se il fast path viene sfidato, chiede la pagina a
   **FlareSolverr** (Chrome headless), ne conserva cookie e User-Agent e serve quel
   body. Il clearance viene riusato dal fast path per circa 25 minuti.

## Architettura

```
CloudStream
        |  http://<host>:8191/fetch?token=...&url={url}
        v
   kisskh-relay --fast--> kisskh.co   (cookie cf_clearance)
        |
        +----solve-----> flaresolverr --> kisskh.co   (Chrome headless)
```

## Requisiti

- solo fast path (cookie copiati a mano): Docker, ~100 MB di RAM;
- solve automatico: aggiungi ~600 MB - 1 GB di RAM libera e ~2 GB di disco per
  l'immagine FlareSolverr.

## Avvio (Docker)

```bash
cd tools/kisskh-relay
# cambia RELAY_TOKEN in docker-compose.yml
docker compose up -d --build
docker compose ps
docker compose logs -f kisskh-relay
```

Il relay parte subito; FlareSolverr, se attivo, impiega 20-40 s al primo avvio.

Health check (mostra anche se il clearance e attivo e quante richieste ha gestito):

```bash
curl -s "http://localhost:8191/healthz"
```

## Avvio senza Docker

```bash
cd tools/kisskh-relay
python -m venv .venv
. .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
RELAY_TOKEN=cambia-questo-token RELAY_HOSTS=kisskh.co,cdnvideo11.shop \
  uvicorn app:app --host 0.0.0.0 --port 8191 --no-access-log
```

## Due modalita per il clearance

- **A — cookie dal browser**: apri `https://kisskh.co` in un browser, supera il
  challenge, copia `cf_clearance` (e `__cf_bm`) in `RELAY_COOKIES` e lo UA esatto
  del browser in `RELAY_USER_AGENT`. Istantaneo, zero CPU, ma il cookie va
  rinnovato quando scade (30 minuti - qualche ora).
- **B — FlareSolverr**: `docker compose --profile solver up -d --build`. Automatico
  ma pesante (Chrome headless) e su box ARM puo non risolvere il challenge. Il
  relay riscalda il clearance sulla **home** del sito, non sull'API.

Le credenziali **non** vanno committate: restano nel `docker-compose.yml` locale o
in variabili d'ambiente. In questo repository il compose contiene solo segnaposto
vuoti.

## Configurazione nel plugin

KissKH -> ingranaggio delle impostazioni -> **Relay Cloudflare (opzionale)**:

```
http://192.168.1.50:8191/fetch?token=cambia-questo-token&url={url}
```

Sostituisci l'IP con quello del box. `{url}` lo sostituisce il plugin con l'URL gia
codificato: lascialo cosi com'e.

Note:

- il relay copre API, cataloghi, dettagli, episodi e i file dei sottotitoli;
- i flussi video e le playlist vanno diretti al CDN e non passano dal relay;
- la prima schermata dopo 25 minuti di inattivita puo richiedere qualche secondo in
  piu (rinnovo del clearance), poi torna veloce.

## Endpoint

| Endpoint | Uso |
|---|---|
| `GET /fetch?url=&token=` | scarica una URL (API, immagini, file `.srt` del CDN) e ne restituisce il body |
| `POST /store?token=&ext=srt` | pubblica un sottotitolo generato (decifrato o tradotto da DeepL); risponde `{"url": "..."}` |
| `GET /sub/<id>.srt` | serve il sottotitolo pubblicato (il player riceve un URL HTTP normale) |
| `GET /healthz` | stato, cookie, statistiche |

`/store` serve perche sulla Android TV il player non riesce a leggere da un server
locale del telefono: il plugin carica il testo sul relay e passa al player un URL
HTTP normale. Senza `/store` funzionano comunque API e sottotitoli `.srt`/`.vtt`.

## Variabili d'ambiente

| Variabile | Default | Significato |
|---|---|---|
| `RELAY_TOKEN` | *(vuoto = nessun controllo)* | token richiesto in `?token=` |
| `RELAY_HOSTS` | `kisskh.co` | host permessi, separati da virgola; include i sottodomini |
| `RELAY_COOKIES` | *(vuoto)* | cookie `cf_clearance=...; __cf_bm=...` copiati a mano |
| `RELAY_USER_AGENT` | *(vuoto)* | UA esatto del browser da cui hai copiato i cookie |
| `RELAY_CLEARANCE_TTL` | `1500` | validita del cookie risolto/iniettato, secondi |
| `RELAY_SOLVER_URL` | *(vuoto = disattivato)* | endpoint FlareSolverr, es. `http://flaresolverr:8191/v1` |
| `RELAY_SOLVER_TIMEOUT` | `120` | tempo massimo concesso a FlareSolverr, secondi |
| `RELAY_SOLVER_WAIT` | `0` | secondi di attesa extra dopo il caricamento (`waitInSeconds`) |
| `RELAY_WARM` | `1` | riscalda il clearance in background (solo con solver) |
| `RELAY_WARM_URL` | home del primo host | pagina usata per il riscaldamento |
| `RELAY_FAST_RETRY` | `600` | dopo un fallimento del fast path, per quanto ignorarlo |
| `RELAY_IMPERSONATE` | `chrome` | profilo TLS di `curl_cffi` |
| `RELAY_TIMEOUT` | `25` | timeout per richiesta, secondi |
| `RELAY_CACHE_TTL` | `30` | cache in memoria per le risposte `200`, secondi (`0` = off) |
| `RELAY_CACHE_ENTRIES` | `256` | numero massimo di risposte in cache |
| `RELAY_MAX_BYTES` | `8388608` | dimensione massima della risposta |
| `RELAY_STORE_TTL` | `21600` | per quanto restano disponibili i sottotitoli pubblicati, secondi |
| `RELAY_STORE_MAX` | `2000000` | dimensione massima di un sottotitolo pubblicato, byte |
| `RELAY_VERBOSE` | `0` | `1` logga ogni richiesta (host, esito) |

Se compare `{"error":"host not allowed","host":"..."}`, aggiungi quell'host a
`RELAY_HOSTS`: il CDN dei sottotitoli puo stare su un dominio separato.

## Test

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:8191/fetch?token=IL_TUO_TOKEN&url=https%3A%2F%2Fkisskh.co%2Fapi%2FDramaList%2FList%3Fpage%3D1%26type%3D0%26sub%3D0%26country%3D0%26status%3D0%26order%3D1"
```

- `200` -> funziona;
- `403` con `{"error":"host not allowed"}` -> host mancante in `RELAY_HOSTS`;
- `403` con HTML "Just a moment…" -> anche FlareSolverr e stato sfidato: vedi
  *Se resta 403*.

Per capire quale strada ha usato (header `X-Relay-Source`):

```bash
curl -s -D- -o /dev/null "http://localhost:8191/fetch?token=IL_TUO_TOKEN&url=https%3A%2F%2Fkisskh.co%2Fapi%2FDramaList%2FList%3Fpage%3D1%26type%3D0%26sub%3D0%26country%3D0%26status%3D0%26order%3D1" | findstr X-Relay
```

- `X-Relay: cache` -> risposta in cache (30 s);
- `X-Relay-Source: solved` -> ha risolto il challenge con FlareSolverr;
- `X-Relay-Source: fast` -> ha riusato il clearance (veloce).

## Se resta 403

1. **Controlla i log**: `docker compose logs flaresolverr | tail -50`. Se vedi
   `Challenge not solved` / `Error solving the challenge`, anche il browser
   headless e bloccato.
2. **Prova un altro IP**: se da un'altra rete la richiesta passa, il problema e
   l'IP di partenza -> sposta il relay su un VPS (Oracle Free Tier, Hetzner) con lo
   stesso `docker compose`.
3. **Evita hosting serverless/edge** (Vercel, Deno, Cloudflare Workers): IP
   datacenter, sfidati piu spesso.

## Sicurezza

- tieni la porta **solo sulla LAN**, non aprire il port forwarding sul router;
- imposta sempre `RELAY_TOKEN`: evita che altri dispositivi della rete usino il
  relay;
- per l'accesso da fuori casa usa Tailscale/WireGuard e metti l'URL Tailscale nel
  campo del plugin;
- i log sono volutamente minimali (`--no-access-log`): non registrano gli URL.

## Manutenzione

Cloudflare cambia le regole nel tempo. Se dopo mesi il relay inizia a restituire
`403`, aggiorna le immagini:

```bash
docker compose pull
docker compose build --no-cache
docker compose up -d
```
