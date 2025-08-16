# ReportWebhook (Paper 1.21.8)

Plugin Paper per inviare report via webhook Discord e gestire lo stato dei report.

## Funzioni
- Comando `/report <giocatore> <motivo>`: invia un embed Discord (colore configurabile) e salva il report come **ricevuto**.
- Comando `/reports`: mostra SOLO i report inviati da te, con stato: `ricevuto`, `confermato`, `rifiutato`.
- Comando `/staffreports`: mostra TUTTI i report (permesso `report.staff`).
  - `/staffreports <id> confirm` → conferma un report.
  - `/staffreports <id> decline` → rifiuta un report (verrà eliminato **dopo 24h**).
- Escape automatico degli underscore `_` per evitare corsivo su Discord.
- Blocco **self-report** (non puoi segnalare te stesso).
- Override di `/report`: intercettiamo il comando con un listener ad alta priorità, così viene sempre gestito dal nostro plugin.

## Configurazione
`plugins/ReportWebhook/config.yml`:
```yaml
webhook-url: "INSERISCI_URL_WEBHOOK"
embed-color: "#FFA500"   # Colore HEX
```

## Permessi
- `report.use` (default: true) → usare `/report`
- `report.staff` (default: op) → usare `/staffreports`

## Compilazione
Assicurati di avere Java 21 e Maven:
```bash
mvn clean package
```
Troverai il JAR in `target/ReportWebhookPaper.jar`.

## Installazione
1. Copia il JAR in `plugins/` del server Paper.
2. Avvia il server, inserisci il webhook nel config e riavvia.

## Note
- I report rifiutati vengono eliminati automaticamente **24 ore dopo** il rifiuto.
- Nessun avatar o timestamp negli embed Discord come richiesto.
