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
 * Сніг і заморожування води в нових чанках. Блоки НЕ міняємо всередині події завантаження:
 * це тягне сусідні чанки і підвішує сервер. Роботу відкладаємо на наступний тік і без фізики.
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
        Chunk c = e.getChunk();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (c.isLoaded()) {
                build(w, c);
            }
        });
    }

    private void build(World w, Chunk c) {
        double chance = plugin.getConfig().getDouble("worldgen.snow.chance", 1.0);
        boolean allBiomes = plugin.getConfig().getBoolean("worldgen.snow.all-biomes", true);
        Settings s = plugin.settings();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = (c.getX() << 4) + dx;
                int z = (c.getZ() << 4) + dz;
                if (rnd.nextDouble() >= chance) {
                    continue;
                }
                int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block ground = w.getBlockAt(x, top, z);
                Material gm = ground.getType();
                if (!gm.isSolid() || gm == Material.ICE || gm == Material.PACKED_ICE
                        || gm == Material.BLUE_ICE || gm == Material.SNOW_BLOCK
                        || gm == Material.SNOW || gm.name().contains("LEAVES")
                        || gm.name().contains("LOG") || gm.name().contains("WOOD")) {
                    continue;
                }
                if (!allBiomes && plugin.profile(ground).equals(s.warmProfile)) {
                    continue;
                }
                Block above = ground.getRelative(0, 1, 0);
                Material am = above.getType();
                if (am == Material.SNOW) {
                    continue; // вже є сніг: не нарощуємо шари
                }
                if (!am.isAir()) {
                    continue; // рослини (трава, ягоди, квіти) не чіпаємо: сніг лише на порожнє місце
                }
                Snow snow = (Snow) Material.SNOW.createBlockData();
                snow.setLayers(1 + rnd.nextInt(Math.min(2, snow.getMaximumLayers())));
                above.setBlockData(snow, false);
            }
        }

        if (plugin.getConfig().getBoolean("worldgen.ice.enabled", false)) {
            freezeWater(w, c);
        }
    }

    /** Уся вода в чанку стає льодом (вмикається worldgen.ice.enabled). Без фізики. */
    private void freezeWater(World w, Chunk c) {
        int maxY = w.getSeaLevel() + 1;
        int minY = w.getMinHeight();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = (c.getX() << 4) + dx;
                int z = (c.getZ() << 4) + dz;
                for (int y = maxY; y >= minY; y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == Material.WATER) {
                        b.setType(Material.ICE, false);
                    }
                }
            }
        }
    }
}
