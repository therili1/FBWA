package com.finalbreach.winter;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Лід наростає від берегів: замерзає смуга води біля суші (ice.shore-depth блоків),
 * глибина лишається рідкою. Тане, коли потепліло або поруч тепло котельні.
 * Сканує колонки (без пропусків) лише навколо гравців.
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
        int radius = Math.max(4, cfg.getInt("ice.radius", 20));
        int depth = Math.max(1, cfg.getInt("ice.shore-depth", 5));
        int cap = Math.max(20, cfg.getInt("ice.max-per-tick", 300));

        int changed = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            World w = p.getWorld();
            if (!plugin.isManaged(w)) {
                continue;
            }
            int px = p.getLocation().getBlockX();
            int pz = p.getLocation().getBlockZ();
            for (int x = px - radius; x <= px + radius && changed < cap; x++) {
                for (int z = pz - radius; z <= pz + radius && changed < cap; z++) {
                    int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m == Material.WATER) {
                        if (!nearShore(w, x, z, depth)) {
                            continue;
                        }
                        if (heat.growthTemp(b) < freeze) {
                            b.setType(Material.ICE, false);
                            changed++;
                        }
                    } else if (m == Material.ICE) {
                        if (heat.growthTemp(b) > thaw) {
                            b.setType(Material.WATER, false);
                            changed++;
                        }
                    }
                }
            }
        }
    }

    /** Чи є суша (не вода і не лід) у межах depth блоків по будь-якому з чотирьох напрямків. */
    private boolean nearShore(World w, int x, int z, int depth) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            for (int k = 1; k <= depth; k++) {
                int nx = x + d[0] * k;
                int nz = z + d[1] * k;
                int ny = w.getHighestBlockYAt(nx, nz, HeightMap.WORLD_SURFACE);
                Material m = w.getBlockAt(nx, ny, nz).getType();
                if (m != Material.WATER && m != Material.ICE) {
                    return true;
                }
            }
        }
        return false;
    }
}
