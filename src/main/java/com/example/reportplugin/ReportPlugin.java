package com.example.reportplugin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class ReportPlugin extends JavaPlugin implements Listener {

    private String webhookUrl;
    private int embedColor;
    private DataSource dataSource;
    private String dbType; // sqlite | mysql

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigValues();
        setupDataSource();
        createTableIfNotExists();

        // Register commands (executors handled in onCommand override)
        Objects.requireNonNull(getCommand("report")).setExecutor(this::onReportCommand);
        Objects.requireNonNull(getCommand("reports")).setExecutor(this::onReportsCommand);
        Objects.requireNonNull(getCommand("staffreports")).setExecutor(this::onStaffReportsCommand);

        // Force our /report to win
        getServer().getPluginManager().registerEvents(this, this);

        // Periodic purge every 10 minutes (48h rule)
        new BukkitRunnable() {
            @Override public void run() { purgeOldReports(Duration.ofHours(48)); }
        }.runTaskTimer(this, 20L*60, 20L*60*10);
    }

    @Override
    public void onDisable() {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    private void reloadConfigValues() {
        reloadConfig();
        webhookUrl = getConfig().getString("webhook-url", "");
        String hex = getConfig().getString("embed-color", "#FFA500");
        embedColor = parseHexToInt(hex);
        dbType = getConfig().getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
    }

    private int parseHexToInt(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return Integer.parseInt(h, 16) & 0xFFFFFF;
    }

    private void setupDataSource() {
        boolean enabled = getConfig().getBoolean("database.enabled", true);
        if (!enabled) {
            // comunque attiva SQLite locale, così salviamo sempre su DB
            dbType = "sqlite";
        }
        HikariConfig cfg = new HikariConfig();
        if ("mysql".equals(dbType)) {
            String host = getConfig().getString("database.host", "localhost");
            int port = getConfig().getInt("database.port", 3306);
            String name = getConfig().getString("database.name", "reports");
            String user = getConfig().getString("database.user", "root");
            String pass = getConfig().getString("database.password", "password");
            String jdbc = "jdbc:mysql://" + host + ":" + port + "/" + name + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
            cfg.setJdbcUrl(jdbc);
            cfg.setUsername(user);
            cfg.setPassword(pass);
            cfg.setMaximumPoolSize(5);
        } else {
            String file = getConfig().getString("database.file", "reports.db");
            String jdbc = "jdbc:sqlite:" + getDataFolder().toPath().resolve(file).toString();
            new java.io.File(getDataFolder(), file).getParentFile().mkdirs();
            cfg.setJdbcUrl(jdbc);
            cfg.setMaximumPoolSize(1);
        }
        dataSource = new HikariDataSource(cfg);
    }

    private void createTableIfNotExists() {
        String sql;
        if ("mysql".equals(dbType)) {
            sql = "CREATE TABLE IF NOT EXISTS reports (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT," +
                    "reporter_uuid VARCHAR(36) NOT NULL," +
                    "reporter_name VARCHAR(16) NOT NULL," +
                    "reported_name VARCHAR(16) NOT NULL," +
                    "reason TEXT NOT NULL," +
                    "status VARCHAR(16) NOT NULL," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL," +
                    "PRIMARY KEY (id)" +
                    ")";
        } else {
            sql = "CREATE TABLE IF NOT EXISTS reports (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "reporter_uuid TEXT NOT NULL," +
                    "reporter_name TEXT NOT NULL," +
                    "reported_name TEXT NOT NULL," +
                    "reason TEXT NOT NULL," +
                    "status TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL" +
                    ")";
        }
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            getLogger().severe("Errore creazione tabella: " + e.getMessage());
        }
    }

    // ===== Command override via preprocess =====
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreprocess(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim();
        if (msg.equalsIgnoreCase("/report") || msg.toLowerCase().startsWith("/report ")) {
            e.setCancelled(true);
            String[] parts = msg.substring(1).split("\\s+");
            if (parts.length < 3) {
                e.getPlayer().sendMessage(ChatColor.RED + "Uso corretto: /report <giocatore> <motivo>");
                return;
            }
            String target = parts[1];
            String reason = String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length));
            dispatchReport(e.getPlayer(), target, reason);
        }
    }

    // ===== Commands =====
    private boolean onReportCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Allow both players and console to report. Console reporter will be saved as "Server" with uuid "SERVER".
        if (sender instanceof Player) {
            Player p = (Player) sender;
            if (!p.hasPermission("report.use")) {
                p.sendMessage(ChatColor.RED + "Non hai il permesso.");
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(ChatColor.RED + "Uso corretto: /report <giocatore> <motivo>");
                return true;
            }
            String target = args[0];
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            dispatchReport(p, target, reason);
            return true;
        } else {
            // Console or other non-player sender
            if (!sender.hasPermission("report.use")) {
                sender.sendMessage(ChatColor.RED + "Solo i giocatori possono usare questo comando.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Uso corretto: /report <giocatore> <motivo>");
                return true;
            }
            String target = args[0];
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            long id = insertReport("SERVER", "Server", target, reason);
            sendDiscordEmbed(escapeUnderscore(target), escapeUnderscore("Server"), escapeUnderscore(reason));
            sender.sendMessage(ChatColor.GREEN + "Report #" + id + " inviato su " + target + " per: " + reason);
            String notify = ChatColor.GOLD + "[REPORT] #" + id + " Server \u2192 " + target + " : " + reason;
            Bukkit.getOnlinePlayers().stream().filter(pl -> pl.hasPermission("report.staff")).forEach(pl -> pl.sendMessage(notify));
            return true;
        }
    }

    private boolean onReportsCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Solo i giocatori."); return true; }
        Player p = (Player) sender;
        List<ReportRow> mine = getReportsByReporter(p.getUniqueId().toString());
        if (mine.isEmpty()) { p.sendMessage(ChatColor.YELLOW + "Non hai ancora inviato report."); return true; }
        p.sendMessage(ChatColor.AQUA + "I tuoi report:");
        for (ReportRow r : mine) {
            ChatColor cc = colorByStatus(r.status);
            p.sendMessage(cc + "#" + r.id + ChatColor.GRAY + " → " + r.reportedName + ChatColor.GRAY + " | " + r.reason + ChatColor.GRAY + " | " + r.status.toLowerCase());
        }
        return true;
    }

    private boolean onStaffReportsCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("report.staff")) { sender.sendMessage(ChatColor.RED + "Non hai il permesso."); return true; }
        if (args.length == 0) {
            List<ReportRow> all = getAllReports();
            if (all.isEmpty()) { sender.sendMessage(ChatColor.YELLOW + "Nessun report."); return true; }
            sender.sendMessage(ChatColor.AQUA + "Tutti i report:");
            for (ReportRow r : all) {
                ChatColor cc = colorByStatus(r.status);
                sender.sendMessage(cc + "#" + r.id + ChatColor.GRAY + " | " + r.reporterName + " → " + r.reportedName + ChatColor.GRAY + " | " + r.reason + ChatColor.GRAY + " | " + r.status.toLowerCase());
            }
            return true;
        }
        if (args.length == 2) {
            long id;
            try { id = Long.parseLong(args[0]); }
            catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "ID non valido."); return true; }
            String action = args[1].toLowerCase(Locale.ROOT);
            if ("confirm".equals(action)) {
                updateStatus(id, "CONFERMATO");
                sender.sendMessage(ChatColor.GREEN + "Report #" + id + " confermato. Sarà eliminato in 48h.");
                return true;
            } else if ("decline".equals(action)) {
                updateStatus(id, "RIFIUTATO");
                sender.sendMessage(ChatColor.RED + "Report #" + id + " rifiutato. Sarà eliminato in 48h.");
                return true;
            } else if ("remove".equals(action) || "delete".equals(action)) {
                // Immediate deletion by staff
                deleteReport(id);
                sender.sendMessage(ChatColor.GREEN + "Report #" + id + " eliminato manualmente.");
                return true;
            } else {
        sender.sendMessage(ChatColor.RED + "Uso: /staffreports <id> <confirm|decline|remove>");
                return true;
            }
        }
    sender.sendMessage(ChatColor.RED + "Uso: /staffreports [id] <confirm|decline|remove>");
        return true;
    }

    private void dispatchReport(Player p, String target, String reason) {
        if (target.equalsIgnoreCase(p.getName())) {
            p.sendMessage(ChatColor.RED + "Non puoi segnalare te stesso!");
            return;
        }
        long id = insertReport(p.getUniqueId().toString(), p.getName(), target, reason);
        sendDiscordEmbed(escapeUnderscore(target), escapeUnderscore(p.getName()), escapeUnderscore(reason));
        p.sendMessage(ChatColor.GREEN + "Report #" + id + " inviato su " + target + " per: " + reason);
        String notify = ChatColor.GOLD + "[REPORT] #" + id + " " + p.getName() + " → " + target + " : " + reason;
        Bukkit.getOnlinePlayers().stream().filter(pl -> pl.hasPermission("report.staff")).forEach(pl -> pl.sendMessage(notify));
    }

    private ChatColor colorByStatus(String s) {
        switch (s.toUpperCase(Locale.ROOT)) {
            case "CONFERMATO": return ChatColor.GREEN;
            case "RIFIUTATO": return ChatColor.RED;
            default: return ChatColor.YELLOW;
        }
    }

    // ===== DB Layer =====
    private void purgeOldReports(Duration d) {
        long cutoff = Instant.now().minus(d).toEpochMilli();
        String sql = "DELETE FROM reports WHERE status <> 'RICEVUTO' AND updated_at <= ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            int rows = ps.executeUpdate();
            if (rows > 0) getLogger().info("Puliti " + rows + " report scaduti.");
        } catch (SQLException e) {
            getLogger().warning("Errore purge: " + e.getMessage());
        }
    }

    private long insertReport(String reporterUuid, String reporterName, String reported, String reason) {
        String sql = "INSERT INTO reports (reporter_uuid, reporter_name, reported_name, reason, status, created_at, updated_at) VALUES (?,?,?,?,?, ?, ?)";
        long now = Instant.now().toEpochMilli();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, reporterUuid);
            ps.setString(2, reporterName);
            ps.setString(3, reported);
            ps.setString(4, reason);
            ps.setString(5, "RICEVUTO");
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            getLogger().severe("Errore insert report: " + e.getMessage());
        }
        return -1;
    }

    private void updateStatus(long id, String newStatus) {
        String sql = "UPDATE reports SET status = ?, updated_at = ? WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setLong(2, Instant.now().toEpochMilli());
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Errore update status: " + e.getMessage());
        }
    }

    private List<ReportRow> getReportsByReporter(String reporterUuid) {
        String sql = "SELECT id, reporter_name, reported_name, reason, status FROM reports WHERE reporter_uuid = ? ORDER BY id DESC";
        List<ReportRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reporterUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ReportRow r = new ReportRow();
                    r.id = rs.getLong("id");
                    r.reporterName = rs.getString("reporter_name");
                    r.reportedName = rs.getString("reported_name");
                    r.reason = rs.getString("reason");
                    r.status = rs.getString("status");
                    out.add(r);
                }
            }
        } catch (SQLException e) {
            getLogger().severe("Errore select reports: " + e.getMessage());
        }
        return out;
    }

    private List<ReportRow> getAllReports() {
        String sql = "SELECT id, reporter_name, reported_name, reason, status FROM reports ORDER BY id DESC";
        List<ReportRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ReportRow r = new ReportRow();
                r.id = rs.getLong("id");
                r.reporterName = rs.getString("reporter_name");
                r.reportedName = rs.getString("reported_name");
                r.reason = rs.getString("reason");
                r.status = rs.getString("status");
                out.add(r);
            }
        } catch (SQLException e) {
            getLogger().severe("Errore select all: " + e.getMessage());
        }
        return out;
    }

    static class ReportRow {
        long id;
        String reporterName;
        String reportedName;
        String reason;
        String status;
    }

    private void deleteReport(long id) {
        String sql = "DELETE FROM reports WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Errore delete report: " + e.getMessage());
        }
    }

    // ====== Discord webhook ======
    private String escapeUnderscore(String s) {
        return s.replace("_", "\\_");
    }
    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private void sendDiscordEmbed(String target, String reporter, String reason) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            String payload = "{"
                    + "\"embeds\":[{"
                    + "\"title\":\"\\uD83D\\uDCE2 Nuova Segnalazione\","
                    + "\"color\":" + embedColor + ","
                    + "\"fields\":["
                    + "{\"name\":\"\\uD83D\\uDC64 Giocatore Segnalato\",\"value\":\"" + jsonEscape(target) + "\"},"
                    + "{\"name\":\"\\uD83D\\uDCDD Segnalato da\",\"value\":\"" + jsonEscape(reporter) + "\"},"
                    + "{\"name\":\"\\u26A0\\uFE0F Motivo\",\"value\":\"" + jsonEscape(reason) + "\"}"
                    + "]"
                    + "}]}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.getInputStream().close();
            if (code != 204 && code != 200) {
                getLogger().warning("Discord webhook status: " + code);
            }
        } catch (Exception e) {
            getLogger().warning("Errore invio webhook: " + e.getMessage());
        }
    }
}
