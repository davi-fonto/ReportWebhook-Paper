package com.tuo.reportwebhook;

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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class ReportWebhookPlugin extends JavaPlugin implements Listener {

    private String webhookUrl;
    private int embedColorRgb; // integer RGB
    private File reportsFile;
    private FileConfiguration reportsCfg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();
        // Reports file
        reportsFile = new File(getDataFolder(), "reports.yml");
        if (!reportsFile.exists()) {
            try {
                getDataFolder().mkdirs();
                reportsFile.createNewFile();
                reportsCfg = YamlConfiguration.loadConfiguration(reportsFile);
                reportsCfg.set("nextId", 1);
                reportsCfg.set("reports", new ArrayList<>());
                reportsCfg.save(reportsFile);
            } catch (IOException e) {
                getLogger().severe("Impossibile creare reports.yml: " + e.getMessage());
            }
        } else {
            reportsCfg = YamlConfiguration.loadConfiguration(reportsFile);
            if (!reportsCfg.isInt("nextId")) reportsCfg.set("nextId", 1);
            if (!reportsCfg.isList("reports")) reportsCfg.set("reports", new ArrayList<>());
            saveReports();
        }

        // Register commands (basic)
        Objects.requireNonNull(getCommand("report")).setExecutor(this::onReportCommand);
        Objects.requireNonNull(getCommand("reports")).setExecutor(this::onReportsCommand);
        Objects.requireNonNull(getCommand("staffreports")).setExecutor(this::onStaffReportsCommand);

        // Listener to force our /report to take precedence
        getServer().getPluginManager().registerEvents(this, this);

        // Periodic purge of declined >24h
        new BukkitRunnable() {
            @Override public void run() { purgeDeclinedOlderThan(Duration.ofHours(24)); }
        }.runTaskTimer(this, 20L * 60, 20L * 60 * 10); // after 1m, then every 10m
    }

    private void reloadLocalConfig() {
        reloadConfig();
        webhookUrl = getConfig().getString("webhook-url", "");
        String hex = getConfig().getString("embed-color", "#FFA500").trim();
        embedColorRgb = parseHexColor(hex);
    }

    private int parseHexColor(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        int rgb = Integer.parseInt(h, 16) & 0xFFFFFF;
        return rgb;
    }

    private void saveReports() {
        try { reportsCfg.save(reportsFile); }
        catch (IOException e) { getLogger().severe("Impossibile salvare reports.yml: " + e.getMessage()); }
    }

    // ========== Command intercept to override other /report ==========
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreprocess(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim();
        if (msg.equalsIgnoreCase("/report") || msg.toLowerCase().startsWith("/report ")) {
            // Cancel and dispatch ours
            e.setCancelled(true);
            String[] parts = msg.substring(1).split("\\s+"); // remove leading /
            // /report <player> <reason...>
            if (parts.length < 3) {
                e.getPlayer().sendMessage(ChatColor.RED + "Uso corretto: /report <giocatore> <motivo>");
                return;
            }
            String target = parts[1];
            String reason = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
            dispatchOurReport(e.getPlayer(), target, reason);
        }
    }

    private boolean onReportCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Solo i giocatori possono usare questo comando.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("report.use")) {
            p.sendMessage(ChatColor.RED + "Non hai il permesso di usare questo comando.");
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Uso corretto: /report <giocatore> <motivo>");
            return true;
        }
        String target = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        dispatchOurReport(p, target, reason);
        return true;
    }

    private void dispatchOurReport(Player p, String target, String reason) {
        if (target.equalsIgnoreCase(p.getName())) {
            p.sendMessage(ChatColor.RED + "Non puoi segnalare te stesso!");
            return;
        }
        int id = reportsCfg.getInt("nextId", 1);
        reportsCfg.set("nextId", id + 1);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("segnalante", p.getUniqueId().toString());
        entry.put("segnalanteName", p.getName());
        entry.put("segnalato", target);
        entry.put("motivo", reason);
        entry.put("stato", "RICEVUTO");
        entry.put("createdAt", Instant.now().toEpochMilli());
        entry.put("declinedAt", null);

        List<Map<?,?>> list = (List<Map<?,?>>) reportsCfg.getList("reports");
        if (list == null) list = new ArrayList<>();
        list.add(entry);
        reportsCfg.set("reports", list);
        saveReports();

        sendDiscordEmbed(escapeUnderscore(target), escapeUnderscore(p.getName()), escapeUnderscore(reason));

        p.sendMessage(ChatColor.GREEN + "Report #" + id + " inviato su " + target + " per: " + reason);
        String notify = ChatColor.GOLD + "[REPORT] #" + id + " " + p.getName() + " → " + target + " : " + reason;
        Bukkit.getOnlinePlayers().stream()
                .filter(pl -> pl.hasPermission("report.staff"))
                .forEach(pl -> pl.sendMessage(notify));
    }

    private boolean onReportsCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Solo i giocatori possono usare questo comando.");
            return true;
        }
        Player p = (Player) sender;
        String uuid = p.getUniqueId().toString();
        List<Map<?,?>> list = (List<Map<?,?>>) reportsCfg.getList("reports");
        if (list == null) {
            p.sendMessage(ChatColor.YELLOW + "Nessun report trovato.");
            return true;
        }
        List<Map<?,?>> mine = list.stream()
                .filter(m -> uuid.equals(String.valueOf(m.get("segnalante"))))
                .collect(Collectors.toList());
        if (mine.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Non hai ancora inviato report.");
            return true;
        }
        p.sendMessage(ChatColor.AQUA + "I tuoi report:");
        for (Map<?,?> m : mine) {
            int id = (int) m.get("id");
            String stato = String.valueOf(m.get("stato"));
            String segnalato = String.valueOf(m.get("segnalato"));
            String motivo = String.valueOf(m.get("motivo"));
            ChatColor color = statoColor(stato);
            p.sendMessage(color + "#" + id + ChatColor.GRAY + " → " + segnalato + ChatColor.GRAY + " | " + motivo + ChatColor.GRAY + " | " + stato.toLowerCase());
        }
        return true;
    }

    private boolean onStaffReportsCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("report.staff")) {
            sender.sendMessage(ChatColor.RED + "Non hai il permesso.");
            return true;
        }
        List<Map<?,?>> list = (List<Map<?,?>>) reportsCfg.getList("reports");
        if (list == null) { sender.sendMessage(ChatColor.YELLOW + "Nessun report."); return true; }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "Tutti i report:");
            for (Map<?,?> m : list) {
                int id = (int) m.get("id");
                String stato = String.valueOf(m.get("stato"));
                String segnalanteName = String.valueOf(m.get("segnalanteName"));
                String segnalato = String.valueOf(m.get("segnalato"));
                String motivo = String.valueOf(m.get("motivo"));
                ChatColor color = statoColor(stato);
                sender.sendMessage(color + "#" + id + ChatColor.GRAY + " | " + segnalanteName + " → " + segnalato + ChatColor.GRAY + " | " + motivo + ChatColor.GRAY + " | " + stato.toLowerCase());
            }
            return true;
        }
        if (args.length == 2) {
            int id;
            try { id = Integer.parseInt(args[0]); }
            catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "ID non valido."); return true; }
            String action = args[1].toLowerCase(Locale.ROOT);
            int idx = indexOfId(list, id);
            if (idx == -1) { sender.sendMessage(ChatColor.RED + "Report non trovato."); return true; }
            Map m = (Map) list.get(idx);
            if (action.equals("confirm")) {
                m.put("stato", "CONFERMATO");
                sender.sendMessage(ChatColor.GREEN + "Report #" + id + " confermato.");
            } else if (action.equals("decline")) {
                m.put("stato", "RIFIUTATO");
                m.put("declinedAt", Instant.now().toEpochMilli());
                sender.sendMessage(ChatColor.RED + "Report #" + id + " rifiutato. Sarà eliminato in 24h.");
            } else {
                sender.sendMessage(ChatColor.RED + "Uso: /staffreports <id> <confirm|decline>");
                return true;
            }
            reportsCfg.set("reports", list);
            saveReports();
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Uso: /staffreports [id] <confirm|decline>");
        return true;
    }

    private int indexOfId(List<Map<?,?>> list, int id) {
        for (int i=0;i<list.size();i++) {
            Object v = list.get(i).get("id");
            if (v instanceof Integer && ((Integer)v) == id) return i;
            if (v instanceof Number && ((Number)v).intValue() == id) return i;
        }
        return -1;
    }

    private ChatColor statoColor(String stato) {
        switch (stato.toUpperCase(Locale.ROOT)) {
            case "CONFERMATO": return ChatColor.GREEN;
            case "RIFIUTATO": return ChatColor.RED;
            default: return ChatColor.YELLOW;
        }
    }

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
                    + "\"color\":" + embedColorRgb + ","
                    + "\"fields\":["
                    + "{\"name\":\"Giocatore Segnalato\",\"value\":\"" + jsonEscape(target) + "\"},"
                    + "{\"name\":\"Segnalato da\",\"value\":\"" + jsonEscape(reporter) + "\"},"
                    + "{\"name\":\"Motivo\",\"value\":\"" + jsonEscape(reason) + "\"}"
                    + "]"
                    + "}]}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code != 204 && code != 200) {
                getLogger().warning("Webhook Discord status: " + code);
            }
        } catch (Exception e) {
            getLogger().warning("Errore invio webhook: " + e.getMessage());
        }
    }

    private void purgeDeclinedOlderThan(Duration d) {
        long cutoff = Instant.now().minus(d).toEpochMilli();
        List<Map<?,?>> list = (List<Map<?,?>>) reportsCfg.getList("reports");
        if (list == null || list.isEmpty()) return;
        List<Map<?,?>> kept = new ArrayList<>();
        for (Map<?,?> m : list) {
            String stato = String.valueOf(m.get("stato"));
            Object declinedAt = m.get("declinedAt");
            if ("RIFIUTATO".equalsIgnoreCase(stato) && declinedAt instanceof Number) {
                long when = ((Number) declinedAt).longValue();
                if (when <= cutoff) {
                    // drop
                    continue;
                }
            }
            kept.add(m);
        }
        if (kept.size() != list.size()) {
            reportsCfg.set("reports", kept);
            saveReports();
        }
    }
}
