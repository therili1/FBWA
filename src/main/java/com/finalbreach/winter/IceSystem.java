package com.finalbreach.winter;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Лід наростає фронтом від берега: за кожен цикл холоду замерзає один шар води,
 * який стикається з льодом або сушею. Так річка замерзає поступово, від берегів до середини.
 * Тане, коли потеплішає або поруч тепло котельні. Сканує лише ділянки навколо гравців.
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
        double thawChance = Math.max(0.0, Math.min(1.0, cfg.getDouble("ice.thaw-chance", 0.02)));
        int radius = Math.max(4, cfg.getInt("ice.radius", 20));
        int cap = Math.max(20, cfg.getInt("ice.max-per-tick", 300));

        List<Block> toFreeze = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            World w = p.getWorld();
            if (!plugin.isManaged(w)) {
                continue;
            }
            int px = p.getLocation().getBlockX();
            int pz = p.getLocation().getBlockZ();
            for (int x = px - radius; x <= px + radius; x++) {
                for (int z = pz - radius; z <= pz + radius; z++) {
                    int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m == Material.WATER) {
                        if (toFreeze.size() < cap && isFrontier(w, x, z) && heat.growthTemp(b) < freeze) {
                            toFreeze.add(b);
                        }
                    } else if (m == Material.ICE) {
                        // танення поступове: не весь лід за один тік, а з ймовірністю
                        if (heat.growthTemp(b) > thaw && ThreadLocalRandom.current().nextDouble() < thawChance) {
                            b.setType(Material.WATER, false);
                        }
                    }
                }
            }
        }
        // Заморожуємо всі кандидати одночасно, щоб за один цикл не замерзало більше одного шару.
        for (Block b : toFreeze) {
            b.setType(Material.ICE, false);
        }
    }

    /** Вода на межі: поруч (по сторонах світу) є лід або суша, а не вода. */
    private boolean isFrontier(World w, int x, int z) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0];
            int nz = z + d[1];
            int ny = w.getHighestBlockYAt(nx, nz, HeightMap.WORLD_SURFACE);
            if (w.getBlockAt(nx, ny, nz).getType() != Material.WATER) {
                return true;
            }
        }
        return false;
    }
}
