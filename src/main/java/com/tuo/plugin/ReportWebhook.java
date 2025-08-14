package com.tuo.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ReportWebhook extends JavaPlugin {

    private String webhookUrl;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        webhookUrl = getConfig().getString("webhook-url");
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            getLogger().warning("Nessun webhook-url configurato. Disabilito il plugin.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("report")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Solo i giocatori possono usare questo comando.");
                return true;
            }
            Player reporter = (Player) sender;
            if (args.length < 2) {
                reporter.sendMessage("Uso corretto: /report <giocatore> <motivo>");
                return true;
            }
            String targetName = args[0];
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

            sendToDiscordEmbed(reporter.getName(), targetName, reason);

            reporter.sendMessage("§aSegnalazione inviata allo staff.");
            Bukkit.getLogger().info("[REPORT] " + reporter.getName() + " ha segnalato " + targetName + ": " + reason);
            return true;
        }
        return false;
    }

    private void sendToDiscordEmbed(String reporter, String target, String reason) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");

            JsonObject embed = new JsonObject();
            embed.addProperty("title", "📢 Nuova Segnalazione");
            embed.addProperty("color", 16753920);

            JsonArray fields = new JsonArray();

            JsonObject field1 = new JsonObject();
            field1.addProperty("name", "👤 Giocatore Segnalato");
            field1.addProperty("value", target);
            field1.addProperty("inline", false);
            fields.add(field1);

            JsonObject field2 = new JsonObject();
            field2.addProperty("name", "📝 Segnalato da");
            field2.addProperty("value", reporter);
            field2.addProperty("inline", false);
            fields.add(field2);

            JsonObject field3 = new JsonObject();
            field3.addProperty("name", "⚠️ Motivo");
            field3.addProperty("value", reason);
            field3.addProperty("inline", false);
            fields.add(field3);

            embed.add("fields", fields);

            JsonArray embeds = new JsonArray();
            embeds.add(embed);

            JsonObject payload = new JsonObject();
            payload.add("embeds", embeds);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 204 && responseCode != 200) {
                getLogger().warning("Errore webhook Discord: HTTP " + responseCode);
            }
            conn.disconnect();
        } catch (Exception e) {
            getLogger().warning("Impossibile inviare al webhook: " + e.getMessage());
        }
    }
}
