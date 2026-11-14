package com.finalbreach.winter;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Власний білий список з власним текстом відмови (замість ванільного).
 * Ніки зберігаються у plugins/FinalBreachWinter/whitelist.txt, керування: /wl add|remove|list|reload.
 */
public final class Whitelist implements Listener, CommandExecutor {

    private final WinterPlugin plugin;
    private final File file;
    private final Set<String> names = ConcurrentHashMap.newKeySet();

    public Whitelist(WinterPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "whitelist.txt");
        load();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("access.enabled", true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!enabled()) {
            return;
        }
        if (names.contains(e.getName().toLowerCase(Locale.ROOT))) {
            return;
        }
        String msg = plugin.getConfig().getString("access.message", "Пройдіть реєстрацію у discord.gg/fb");
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text(msg, NamedTextColor.RED));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Використання: /wl add|remove|list|reload [нік]", NamedTextColor.GRAY));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Вкажи нік: /wl add <нік>", NamedTextColor.RED));
                    return true;
                }
                names.add(args[1].toLowerCase(Locale.ROOT));
                save();
                sender.sendMessage(Component.text("Додано до списку: " + args[1], NamedTextColor.GREEN));
            }
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Вкажи нік: /wl remove <нік>", NamedTextColor.RED));
                    return true;
                }
                names.remove(args[1].toLowerCase(Locale.ROOT));
                save();
                sender.sendMessage(Component.text("Видалено зі списку: " + args[1], NamedTextColor.YELLOW));
            }
            case "list" -> sender.sendMessage(Component.text(
                    "У списку (" + names.size() + "): " + String.join(", ", new TreeSet<>(names)), NamedTextColor.AQUA));
            case "reload" -> {
                load();
                sender.sendMessage(Component.text("Список перечитано: " + names.size() + " ників", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Невідома дія. Використання: /wl add|remove|list|reload", NamedTextColor.RED));
        }
        return true;
    }

    private void load() {
        names.clear();
        if (!file.exists()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    names.add(t.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Не вдалося прочитати whitelist.txt: " + ex.getMessage());
        }
    }

    private void save() {
        try {
            plugin.getDataFolder().mkdirs();
            List<String> lines = new ArrayList<>(new TreeSet<>(names));
            Collections.sort(lines);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не вдалося зберегти whitelist.txt: " + ex.getMessage());
        }
    }
}
