package com.finalbreach.winter;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Сніг у всіх біомах.
 *  - Накопичення: щосекунди кілька випадкових відкритих до неба колонок навколо кожного гравця
 *    отримують ще один шар (до snow.max-layers; 8 шарів перетворюються на блок снігу + шари зверху).
 *  - Танення: при температурі від 0° сніг поволі тане до 1 шару. Сніг гравців не чіпаємо.
 *  - Вигляд: ванільний клієнт не малює сніг у теплих біомах, тому там гравцеві показуємо
 *    "ясну" погоду й самі сиплемо сніжинки.
 */
public final class SnowSystem {

    private record Column(Block cell, Block under, int layers, boolean baseOurs) {}

    private final WinterPlugin plugin;
    private final SnowMarks marks;
    private final Random rnd = new Random();
    private final Set<UUID> forced = new HashSet<>();

    public SnowSystem(WinterPlugin plugin, SnowMarks marks) {
        this.plugin = plugin;
        this.marks = marks;
    }

    // ---------- раз на секунду ----------

    public void tick() {
        Settings s = plugin.settings();
        for (World w : Bukkit.getWorlds()) {
            if (!plugin.isManaged(w)) {
                continue;
            }
            List<Player> players = w.getPlayers();
            if (players.isEmpty()) {
                continue;
            }
            Climate c = plugin.climate(w);
            long time = w.getFullTime();
            Climate.Precip precip = c.at(time).precip();
            for (Player p : players) {
                if (s.snowParticles) {
                    updateView(p, precip != Climate.Precip.CLEAR);
                }
                if (!s.snowEnabled) {
                    continue;
                }
                try {
                    work(p, w, c, time, s, precip);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Помилка снігу біля " + p.getName() + ": " + ex);
                }
            }
        }
        // гравці, що пішли зі світів із зимою
        for (Iterator<UUID> it = forced.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !plugin.isManaged(p.getWorld())) {
                if (p != null) {
                    p.resetPlayerWeather();
                }
                it.remove();
            }
        }
    }

    private void work(Player p, World w, Climate c, long time, Settings s, Climate.Precip precip) {
        Location l = p.getLocation();
        int px = l.getBlockX();
        int pz = l.getBlockZ();
        int r = s.snowRadius;

        if (precip != Climate.Precip.CLEAR) {
            int n = (int) Math.round(s.snowSamples * (precip == Climate.Precip.BLIZZARD ? s.blizzardMult : 1.0));
            for (int i = 0; i < n; i++) {
                Block top = column(w, px + rnd.nextInt(2 * r + 1) - r, pz + rnd.nextInt(2 * r + 1) - r);
                if (top == null) {
                    continue;
                }
                if (c.temp(time, plugin.profile(top)) < 0.0) {
                    Column col = read(top);
                    if (col != null) {
                        accumulate(col, s);
                    }
                }
            }
        }

        for (int i = 0; i < s.meltSamples; i++) {
            Block top = column(w, px + rnd.nextInt(2 * r + 1) - r, pz + rnd.nextInt(2 * r + 1) - r);
            if (top == null) {
                continue;
            }
            double t = c.temp(time, plugin.profile(top));
            if (t >= 0.0 && rnd.nextDouble() < (1.0 + t) / 3.0) {
                Column col = read(top);
                if (col != null) {
                    melt(col);
                }
            }
        }
    }

    // ---------- накопичення і танення ----------

    private void accumulate(Column c, Settings s) {
        int total = (c.baseOurs() ? 8 : 0) + c.layers();
        if (total >= s.maxLayers) {
            return;
        }
        if (c.layers() > 0 && marks.isPlayer(c.cell())) {
            return;
        }
        if (c.layers() == 0) {
            if (supports(c.under())) {
                setLayers(c.cell(), 1);
            }
        } else if (c.layers() < 8) {
            setLayers(c.cell(), c.layers() + 1);
        } else {
            // 8 шарів -> блок снігу (наш) + 1 шар зверху
            Block above = c.cell().getRelative(BlockFace.UP);
            if (!above.getType().isAir()) {
                return;
            }
            c.cell().setType(Material.SNOW_BLOCK, false);
            marks.setPlayer(c.cell(), false);
            marks.setOurs(c.cell(), true);
            setLayers(above, 1);
        }
    }

    private void melt(Column c) {
        if (c.layers() > 0 && marks.isPlayer(c.cell())) {
            return;
        }
        int total = (c.baseOurs() ? 8 : 0) + c.layers();
        if (total <= 1) {
            return;
        }
        if (c.layers() > 1) {
            setLayers(c.cell(), c.layers() - 1);
        } else if (c.layers() == 1 && c.baseOurs()) {
            // блок + 1 шар -> 8 шарів на землі
            c.cell().setType(Material.AIR, false);
            setLayers(c.under(), 8);
            marks.setOurs(c.under(), false);
        } else if (c.layers() == 0 && c.baseOurs()) {
            // чистий блок -> 7 шарів
            setLayers(c.under(), 7);
            marks.setOurs(c.under(), false);
        }
    }

    // ---------- допоміжне про колонку ----------

    /** Найвищий блок колонки (тільки в завантажених чанках), без води й повітря. */
    private Block column(World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
        if (top.getType().isAir() || top.isLiquid()) {
            return null;
        }
        return top;
    }

    private Column read(Block top) {
        Block cell = top.getType() == Material.SNOW ? top : top.getRelative(BlockFace.UP);
        Material cm = cell.getType();
        int layers = 0;
        if (cm == Material.SNOW) {
            layers = ((Snow) cell.getBlockData()).getLayers();
        } else if (!soft(cm)) {
            return null;
        }
        Block under = cell.getRelative(BlockFace.DOWN);
        boolean baseOurs = under.getType() == Material.SNOW_BLOCK && marks.isOurs(under);
        return new Column(cell, under, layers, baseOurs);
    }

    /** Що сніг може "накрити" собою: повітря й низька трава. */
    private static boolean soft(Material m) {
        if (m.isAir()) {
            return true;
        }
        String n = m.name();
        return n.equals("SHORT_GRASS") || n.equals("GRASS") || n.equals("FERN") || n.equals("DEAD_BUSH");
    }

    /** Чи тримає блок шар снігу: суцільний верх, не лід і не рідина. */
    private static boolean supports(Block under) {
        Material m = under.getType();
        if (!m.isSolid() || under.isLiquid()) {
            return false;
        }
        String n = m.name();
        if (n.contains("ICE") || n.equals("BARRIER") || n.equals("HONEY_BLOCK") || n.equals("SOUL_SAND")) {
            return false;
        }
        return under.getBoundingBox().getHeight() >= 0.99;
    }

    private static void setLayers(Block b, int layers) {
        if (b.getType() != Material.SNOW) {
            b.setType(Material.SNOW, false);
        }
        Snow data = (Snow) b.getBlockData();
        data.setLayers(Math.max(data.getMinimumLayers(), Math.min(data.getMaximumLayers(), layers)));
        b.setBlockData(data, false);
    }

    // ---------- вигляд для гравця ----------

    /** У теплих біомах ванільний клієнт малює дощ, тому ховаємо його й сиплемо сніжинки самі. */
    private void updateView(Player p, boolean snowing) {
        boolean want = snowing && p.getLocation().getBlock().getTemperature() >= 0.15;
        boolean has = forced.contains(p.getUniqueId());
        if (want && !has) {
            p.setPlayerWeather(WeatherType.CLEAR);
            forced.add(p.getUniqueId());
        } else if (!want && has) {
            p.resetPlayerWeather();
            forced.remove(p.getUniqueId());
        }
    }

    /** Сніжинки для гравців у теплих біомах (кожні 4 тіки). */
    public void particles() {
        if (!plugin.settings().snowParticles) {
            return;
        }
        for (UUID id : forced) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            World w = p.getWorld();
            Climate.Precip pr = plugin.climate(w).at(w.getFullTime()).precip();
            if (pr == Climate.Precip.CLEAR) {
                continue;
            }
            boolean storm = pr == Climate.Precip.BLIZZARD;
            int n = storm ? 26 : 9;
            double wind = storm ? 0.9 : 0.12;
            Location l = p.getLocation();
            for (int i = 0; i < n; i++) {
                double x = l.getX() + (rnd.nextDouble() * 2.0 - 1.0) * 14.0;
                double z = l.getZ() + (rnd.nextDouble() * 2.0 - 1.0) * 14.0;
                int bx = (int) Math.floor(x);
                int bz = (int) Math.floor(z);
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }
                int top = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING);
                double yMin = Math.max(top + 1.0, l.getY() - 3.0);
                double yMax = l.getY() + 9.0;
                if (yMin >= yMax) {
                    continue; // там дах або ми занадто високо над землею
                }
                double y = yMin + rnd.nextDouble() * (yMax - yMin);
                p.spawnParticle(Particle.SNOWFLAKE, x, y, z, 0, wind, -0.6, wind * 0.4, 0.5);
            }
        }
    }

    public void forget(Player p) {
        if (forced.remove(p.getUniqueId())) {
            p.resetPlayerWeather();
        }
    }

    public void resetAll() {
        for (UUID id : forced) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.resetPlayerWeather();
            }
        }
        forced.clear();
    }
}
