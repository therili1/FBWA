package com.finalbreach.winter;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

public class DiceCommands implements CommandExecutor {

    private static final int MAX_SIDES = 100;

    public static void register(JavaPlugin plugin) {
        DiceCommands handler = new DiceCommands();
        plugin.getCommand("flip").setExecutor(handler);
        plugin.getCommand("roll").setExecutor(handler);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ця команда тільки для гравців.");
            return true;
        }

        String result;
        if (cmd.getName().equalsIgnoreCase("flip")) {
            boolean heads = ThreadLocalRandom.current().nextBoolean();
            result = "🪙 " + player.getName() + " підкинув монетку: " + (heads ? "ОРЕЛ" : "РЕШКА");
        } else {
            int sides = 6;
            if (args.length > 0) {
                try {
                    sides = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("Вкажіть кількість граней, наприклад /roll 20");
                    return true;
                }
            }
            if (sides < 2 || sides > MAX_SIDES) {
                sender.sendMessage("Кількість граней має бути від 2 до " + MAX_SIDES);
                return true;
            }
            int value = ThreadLocalRandom.current().nextInt(1, sides + 1);
            result = "🎲 " + player.getName() + " кинув d" + sides + ": " + value;
        }

        Bukkit.broadcastMessage(result);
        return true;
    }
}
