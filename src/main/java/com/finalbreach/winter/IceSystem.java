package com.finalbreach.winter;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Річки й озера замерзають, коли надворі (з урахуванням укриття) холодно,
 * і тануть, коли поруч тепло котельні або потепліло. Сканує лише ділянки навколо гравців.
 */
public final class IceSystem {

    private final WinterPlugin plugin;
    private final HeatField heat;

    public IceSystem(WinterPlugin plugin, HeatField heat) {
        this.plugin = plugin;
        this.heat = heat;
    }

    public void tick() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("ice.enabled", true)) {
            return;
        }
        double freeze = cfg.getDouble("ice.freeze-below", -5.0);
        double thaw = cfg.getDouble("ice.thaw-above", 0.0);
        int radius = Math.max(4, cfg.getInt("ice.radius", 24));
        int cap = Math.max(20, cfg.getInt("ice.max-per-tick", 300));

        int changed = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            World w = p.getWorld();
            if (!plugin.isManaged(w)) {
                continue;
            }
            Location l = p.getLocation();
            int px = l.getBlockX();
            int pz = l.getBlockZ();
            for (int dx = -radius; dx <= radius && changed < cap; dx += 2) {
                for (int dz = -radius; dz <= radius && changed < cap; dz += 2) {
                    int x = px + dx;
                    int z = pz + dz;
                    int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m != Material.WATER && m != Material.ICE) {
                        continue;
                    }
                    double t = heat.growthTemp(b);
                    if (m == Material.WATER && t < freeze) {
                        b.setType(Material.ICE, false);
                        changed++;
                    } else if (m == Material.ICE && t > thaw) {
                        b.setType(Material.WATER, false);
                        changed++;
                    }
                }
            }
        }
    }
}
