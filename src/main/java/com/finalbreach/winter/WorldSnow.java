package com.finalbreach.winter;

import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Одиночні шари снігу в нових чанках. Спрацьовує лише для щойно згенерованих чанків,
 * тому вже існуючий світ не змінюється. Пропускає теплі біоми, дерева, воду й лід.
 */
public final class WorldSnow implements Listener {

    private final WinterPlugin plugin;

    public WorldSnow(WinterPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("worldgen.snow.enabled", true)) {
            return;
        }
        World w = e.getWorld();
        if (!plugin.isManaged(w)) {
            return;
        }
        double chance = plugin.getConfig().getDouble("worldgen.snow.chance", 0.35);
        Chunk c = e.getChunk();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Settings s = plugin.settings();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = (c.getX() << 4) + dx;
                int z = (c.getZ() << 4) + dz;
                if (rnd.nextDouble() >= chance) {
                    continue;
                }
                int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block ground = w.getBlockAt(x, top, z);
                Block above = ground.getRelative(0, 1, 0);
                if (!above.getType().isAir()) {
                    continue;
                }
                Material gm = ground.getType();
                if (!gm.isSolid() || gm == Material.ICE || gm == Material.PACKED_ICE
                        || gm == Material.BLUE_ICE || gm == Material.SNOW_BLOCK
                        || gm == Material.SNOW || gm.name().contains("LEAVES")
                        || gm.name().contains("LOG") || gm.name().contains("WOOD")) {
                    continue;
                }
                if (plugin.profile(ground).equals(s.warmProfile)) {
                    continue;
                }
                Snow snow = (Snow) Material.SNOW.createBlockData();
                snow.setLayers(1 + rnd.nextInt(Math.min(2, snow.getMaximumLayers())));
                above.setBlockData(snow, false);
            }
        }
    }
}
