package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;

import java.util.Random;

/**
 * Теплиця: рослини ростуть залежно від температури в їхньому блоці
 * (надворі + тепло від печей). Нижче greenhouse.min-temp не ростуть,
 * від greenhouse.good-temp ростуть як у ванілі, між ними - повільніше.
 * Тому взимку ферма має бути в закритому приміщенні з пічкою.
 */
public final class Greenhouse implements Listener {

    private final WinterPlugin plugin;
    private final Random rnd = new Random();

    public Greenhouse(WinterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Шанс, що рослина виросте цього разу: 0..1. */
    private double chance(Block b) {
        Settings s = plugin.settings();
        double t = plugin.heat().growthTemp(b);
        return Math.max(0.0, Math.min(1.0, (t - s.growMin) / (s.growGood - s.growMin)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        Block b = e.getBlock();
        if (!plugin.settings().greenhouse || !plugin.isManaged(b.getWorld())) {
            return;
        }
        double c = chance(b);
        if (c <= 0.0 || (c < 1.0 && rnd.nextDouble() > c)) {
            e.setCancelled(true);
        }
    }

    /** Кісткове борошно взимку на морозі не допомагає. */
    @EventHandler(ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        Block b = e.getBlock();
        if (!plugin.settings().greenhouse || !plugin.isManaged(b.getWorld())) {
            return;
        }
        if (chance(b) <= 0.0) {
            e.setCancelled(true);
            if (e.getPlayer() != null) {
                e.getPlayer().sendActionBar(Component.text("Надто холодно, щоб рости.", NamedTextColor.AQUA));
            }
        }
    }
}
