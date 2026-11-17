# 🔒 PriSync

Backup e ripristino di **tutta la configurazione di CloudStream** tra dispositivi (es. telefono → TV) con un PIN a 6 cifre e cifratura end-to-end.

È lo stesso sync presente in **PriSynx → Impostazioni → Altro**, ma come estensione a sé: non contiene provider, serve solo a sincronizzare.

## Installazione

In CloudStream: **Impostazioni → Estensioni → Aggiungi repository** e incolla:

```
https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/prisync.json
```

Installa **PriSync** e tocca l'ingranaggio ⚙️ accanto per aprire il sync.

## Cosa sincronizza

- Repository aggiunti ed elenco dei plugin installati.
- Impostazioni di **tutti** i plugin (non solo PriSynx): chiavi debrid, categorie, liste, ecc.
- Preferenze di CloudStream (player, sottotitoli, tema, …).
- Cronologia, avanzamento, preferiti e segnalibri.

**Plugin mancanti:** i file dei plugin non vengono caricati sul server. Dopo il ripristino, i plugin presenti sul dispositivo di origine ma assenti su quello di destinazione vengono **riscaricati in automatico dai loro repository** al riavvio di CloudStream, già configurati.

- Serve l'aggiornamento automatico dei plugin attivo (predefinito in CloudStream).
- I plugin installati a mano da file, senza repository, non possono essere riscaricati.

## Come si usa

1. **Dispositivo di origine** (es. telefono): apri PriSync, premi **🎲 Genera nuovo PIN casuale** (o scrivine uno a 6 cifre) e poi **📤 Cifra e Invia Backup alla VPS**. Annota il PIN.
2. **Dispositivo di destinazione** (es. TV): apri PriSync, inserisci lo stesso PIN e premi **📥 Scarica e Ripristina da questo PIN**.
3. Il messaggio finale indica quanti plugin verranno scaricati. **Chiudi del tutto CloudStream e riaprilo**: i plugin mancanti si scaricano da soli e tutto è come sul dispositivo di origine.

Per aggiornare il backup basta ripetere il punto 1 con lo stesso PIN.

## Sicurezza

- Cifratura **AES-256-GCM** fatta sul dispositivo: il server riceve solo dati cifrati e non conosce il PIN.
- Anti-bruteforce: dopo 5 PIN errati il server blocca l'accesso per 60 minuti.
- Il codice del server è nella cartella [`server/`](../server/).
- Il ripristino **sovrascrive** le impostazioni del dispositivo di destinazione.

## Sviluppo

Il modulo compila gli stessi sorgenti di [`PriSynx/src/main/kotlin/prisynx/sync`](../PriSynx/src/main/kotlin/prisynx/sync): una modifica lì vale sia per PriSynx sia per PriSync. L'unico file proprio è `PriSyncPlugin.kt` (la finestra delle impostazioni).
