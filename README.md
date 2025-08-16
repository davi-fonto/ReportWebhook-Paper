<<<<<<< Updated upstream
<<<<<<< Updated upstream
# ReportWebhook-Paper
 A minecraft paper plugin to report players directly to a discord webhook!
=======
=======
>>>>>>> Stashed changes
# ReportWebhookPaperDB (Paper 1.21.8)

Plugin completo con:
- Embed Discord con emoji (👤, 📝, ⚠️) e colore HEX configurabile.
- Escape `_` -> `\_` per nomi/motivi su Discord.
- Blocco self-report.
- `/reports` (solo tuoi), `/staffreports` (tutti; confirm/decline).
- Eliminazione automatica **dopo 48h** dei report **CONFERMATI** e **RIFIUTATI** (i **RICEVUTI** restano).
- Storage su **SQLite** (default) o **MySQL** configurabile.
- Override del comando `/report` con listener a priorità **LOWEST**.

## Configurazione
`plugins/ReportWebhookPaperDB/config.yml`:
```yaml
webhook-url: "INSERISCI_URL_WEBHOOK"
embed-color: "#FFA500"

database:
  enabled: true
  type: "sqlite"   # oppure "mysql"
  file: "reports.db"
  host: "localhost"
  port: 3306
  name: "reports"
  user: "root"
  password: "password"
```

## Comandi e permessi
- `/report <giocatore> <motivo>` → `report.use`
- `/reports` → `report.use`
- `/staffreports [id] <confirm|decline>` → `report.staff`

## Compilazione
```bash
mvn clean package
```
Troverai il jar in `target/ReportWebhookPaperDB.jar`.
<<<<<<< Updated upstream
>>>>>>> Stashed changes
=======
>>>>>>> Stashed changes
