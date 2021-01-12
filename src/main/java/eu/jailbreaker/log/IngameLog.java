package eu.jailbreaker.log;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.gson.JsonParser;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

public final class IngameLog extends JavaPlugin implements CommandExecutor {

    private final JsonParser parser = new JsonParser();
    private final HttpClient client = HttpClientBuilder.create().build();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private boolean includePlugins = true;
    private boolean lineBreakInPlugins = true;
    private String permission = "ingamelog.use";
    private String hasteDisplay = "http://haste.jailbreaker.eu/";
    private String hasteServer = "http://haste.jailbreaker.eu/documents";
    private Path latestLogFile = Paths.get("logs", "latest.log");

    private String commandUsage = "§8[§e§lLOG§8] §7Please use: /log";
    private String noPermission = "§8[§e§lLOG§8] §cYou have no permissions to execute this command";
    private String errorWhileCreating = "§8[§e§lLOG§8] §cAn error occurred while reading or creating the log";

    @Override
    public void onEnable() {
        this.getDataFolder().mkdirs();
        this.saveDefaultConfig();

        this.includePlugins = this.getConfig().getBoolean("include-plugins", true);
        this.lineBreakInPlugins = this.getConfig().getBoolean("line-break-in-plugins", true);
        this.latestLogFile = Paths.get(getString("latest-log-file-directory", "logs/latest.log"));
        this.permission = getString("command-permission", "ingamelog.use");
        this.commandUsage = getString("messages.command-usage", "ingamelog.use");
        this.noPermission = getString("messages.no-permission", "ingamelog.use");
        this.errorWhileCreating = getString("messages.error-white-creating", "ingamelog.use");
        this.hasteDisplay = getString("haste-display-url", "http://haste.jailbreaker.eu");
        if (this.hasteDisplay.endsWith("/")) {
            this.hasteDisplay = this.hasteDisplay.substring(0, this.hasteDisplay.length() - 1);
        }
        this.hasteServer = getString("haste-server-url", "http://haste.jailbreaker.eu/documents");
        if (this.hasteServer.endsWith("/")) {
            this.hasteServer = this.hasteServer.substring(0, this.hasteServer.length() - 1);
        }
    }

    @NotNull
    private String getString(String path, String defaultValue) {
        final String content = this.getConfig().getString(path, defaultValue);
        return ChatColor.translateAlternateColorCodes('&', content == null ? defaultValue : content);
    }

    @Override
    public boolean onCommand(CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission(this.permission)) {
            sender.sendMessage(this.noPermission);
            return false;
        }

        if (args.length == 0) {
            final String logContent = this.readLog();
            if (logContent == null) {
                sender.sendMessage(this.errorWhileCreating);
                throw new NullPointerException("log content must not be null");
            }

            final String plugins = this.readPlugins();

            String postContent = this.getConfig().getString("messages.haste-content");
            if (postContent == null) {
                sender.sendMessage(this.errorWhileCreating);
                throw new NullPointerException("raw post-content must not be null");
            }
            postContent = postContent.replace("%creator%", sender.getName())
                    .replace("%timestamp%", this.formatter.format(LocalDateTime.now()))
                    .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                    .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
                    .replace("%plugin_amount%", String.valueOf(Bukkit.getPluginManager().getPlugins().length))
                    .replace("%plugins%", plugins)
                    .replace("%log_content%", logContent)
                    .replace("\\n", "\n");

            final String post = this.post(postContent);
            if (post == null) {
                sender.sendMessage(this.errorWhileCreating);
                throw new NullPointerException("post must not be null");
            }

            final String message = getString("messages.broadcast-to-team", "§8[§e§lLOG§8] §e%creator% created a new log §e%log_url%")
                    .replace("%creator%", sender.getName())
                    .replace("%log_url%", post);
            Bukkit.getConsoleSender().sendMessage(message);
            Bukkit.getOnlinePlayers().stream()
                    .filter(target -> target.hasPermission(this.permission))
                    .forEach(target -> target.sendMessage(message));
        } else {
            sender.sendMessage(this.commandUsage);
        }
        return false;
    }

    @Nullable
    private String post(String content) {
        try {
            final HttpPost post = new HttpPost(this.hasteServer);
            post.setEntity(new StringEntity(content));
            final HttpResponse response = this.client.execute(post);
            final String result = EntityUtils.toString(response.getEntity());
            return this.hasteDisplay + "/" + this.parser.parse(result).getAsJsonObject().get("key").getAsString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @NotNull
    private String readPlugins() {
        final StringBuilder content;
        if (this.includePlugins) {
            final List<String> plugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .map(Plugin::getName)
                    .sorted()
                    .collect(Collectors.toList());

            if (this.lineBreakInPlugins) {
                content = new StringBuilder();
                final List<List<String>> partitions = Lists.partition(plugins, 12);
                for (List<String> partition : partitions) {
                    content.append(Joiner.on(", ").join(partition)).append("\n");
                }
            } else {
                content = new StringBuilder(Joiner.on(", ").join(plugins));
            }
        } else {
            content = new StringBuilder("DISABLED");
        }
        return content.toString();
    }

    @Nullable
    private String readLog() {
        try (Scanner reader = new Scanner(this.latestLogFile.toFile())) {
            Throwable throwable = null;
            final StringBuilder content = new StringBuilder();
            while (reader.hasNextLine()) {
                content.append(reader.nextLine()).append('\n');
            }
            return content.toString();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }
}
