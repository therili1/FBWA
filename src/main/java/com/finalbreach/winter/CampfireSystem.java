package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;

/**
 * Багаття з паливом:
 *  - ванільне багаття ставиться погашеним;
 *  - паливо додається ПКМ: паличка (мало) або вугілля / деревне вугілля (багато);
 *  - запалити можна як у ванілі (кресало, вогняна куля, стріла), але без палива воно одразу згасне;
 *  - поки горить, паливо витрачається; у сніг без даху - швидше.
 * Залишок палива зберігається в самому блоці багаття.
 */
public final class CampfireSystem implements Listener, Runnable {

    private final WinterPlugin plugin;
    private final NamespacedKey fuelKey;

    public CampfireSystem(WinterPlugin plugin) {
        this.plugin = plugin;
        this.fuelKey = new NamespacedKey(plugin, "fuel");
    }

    private static boolean isCampfire(Material m) {
        return m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE;
    }

    // ---------- встановлення і заправка ----------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!plugin.settings().campfireUnlit || !isCampfire(b.getType()) || !plugin.isManaged(b.getWorld())) {
            return;
        }
        // Підпал кресалом теж шле BlockPlaceEvent на тому ж багатті: це не установка, не скидаємо паливо
        if (!isCampfire(e.getItemInHand().getType()) || isCampfire(e.getBlockReplacedState().getType())) {
            return;
        }
        if (b.getBlockData() instanceof Lightable l && l.isLit()) {
            l.setLit(false);
            b.setBlockData(l, false);
        }
        writeFuel(b, 0);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || !isCampfire(b.getType()) || !plugin.isManaged(b.getWorld())) {
            return;
        }
        Settings s = plugin.settings();
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        Material m = item.getType();

        int add = 0;
        if (m == Material.STICK) {
            add = s.stickSeconds;
        } else if (m == Material.COAL || m == Material.CHARCOAL) {
            add = s.coalSeconds;
        } else if (m.name().endsWith("_PLANKS")) {
            add = s.plankSeconds;
        } else if (m.name().endsWith("_LOG")) {
            add = s.logSeconds;
        }

        if (add <= 0) {
            if (m.isAir()) {
                showFuel(p, readFuel(b)); // порожня рука: показуємо скільки палива
            }
            return;
        }

        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);

        int fuel = readFuel(b);
        if (fuel >= s.fuelCap) {
            p.sendActionBar(Component.text("Багаття повне.", NamedTextColor.GRAY));
            return;
        }
        int now = Math.min(s.fuelCap, fuel + add);
        writeFuel(b, now);
        Achievements.countFuel(p);

        if (p.getGameMode() != GameMode.CREATIVE) {
            if (item.getAmount() > 1) {
                ItemStack rest = item.clone();
                rest.setAmount(item.getAmount() - 1);
                p.getInventory().setItemInMainHand(rest);
            } else {
                p.getInventory().setItemInMainHand(null);
            }
        }
        p.playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_CAMPFIRE_CRACKLE, 0.8f, 1.0f);
        showFuel(p, now);
        if (b.getBlockData() instanceof Lightable l && !l.isLit()) {
            p.sendMessage(Component.text("Паливо додано. Підпали багаття кресалом.", NamedTextColor.YELLOW));
        }
    }

    private void showFuel(Player p, int seconds) {
        p.sendActionBar(Component.text(
                String.format("Паливо багаття: %d:%02d", seconds / 60, seconds % 60), NamedTextColor.GOLD));
    }

    // ---------- горіння (раз на секунду) ----------

    @Override
    public void run() {
        Set<Long> done = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (!plugin.isManaged(w)) {
                continue;
            }
            int cx = p.getLocation().getBlockX() >> 4;
            int cz = p.getLocation().getBlockZ() >> 4;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!w.isChunkLoaded(cx + dx, cz + dz)) {
                        continue;
                    }
                    Chunk ch = w.getChunkAt(cx + dx, cz + dz);
                    for (BlockState st : ch.getTileEntities(b -> isCampfire(b.getType()), false)) {
                        long key = HeatField.pack(st.getX(), st.getY(), st.getZ()) * 31 + w.getUID().hashCode();
                        if (done.add(key)) {
                            try {
                                burn(st.getBlock(), w);
                            } catch (Exception ex) {
                                plugin.getLogger().warning("Помилка багаття: " + ex);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Діагностика: що відбувається з паливом у момент підпалу (campfire.debug у конфізі). */
    @EventHandler
    public void onIgnite(BlockIgniteEvent e) {
        Block b = e.getBlock();
        if (!isCampfire(b.getType()) || !plugin.isManaged(b.getWorld())) {
            return;
        }
        if (!plugin.getConfig().getBoolean("campfire.debug", false)) {
            return;
        }
        plugin.getLogger().info("[campfire] ignite " + b.getX() + "," + b.getY() + "," + b.getZ()
                + " cause=" + e.getCause() + " stored(before)=" + readStored(b) + " cache=" + fuelCache.get(cacheKey(b)));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.getLogger().info(
                "[campfire] +1 tick " + b.getX() + "," + b.getY() + "," + b.getZ()
                        + " stored=" + readStored(b) + " lit=" + (b.getBlockData() instanceof Lightable l && l.isLit())), 1L);
    }

    private void burn(Block b, World w) {
        if (!(b.getBlockData() instanceof Lightable l) || !l.isLit()) {
            return; // не горить: паливо не витрачається
        }
        Settings s = plugin.settings();
        Integer stored = readStored(b);
        int fuel = stored == null ? s.unknownLitSeconds : stored; // запалене зі світу: дамо запас

        int rate = 1;
        boolean snowing = plugin.climate(w).at(w.getFullTime()).precip() != Climate.Precip.CLEAR;
        if (snowing && w.getHighestBlockYAt(b.getX(), b.getZ(), HeightMap.MOTION_BLOCKING) <= b.getY()) {
            rate = s.exposedBurn;
        }
        fuel -= rate;

        if (plugin.getConfig().getBoolean("campfire.debug", false) && fuel <= 0) {
            plugin.getLogger().info("[campfire] out " + b.getX() + "," + b.getY() + "," + b.getZ()
                    + " stored=" + stored + " fuel=" + fuel);
        }
        if (fuel <= 0) {
            l.setLit(false);
            b.setBlockData(l, false);
            writeFuel(b, 0);
            Location c = b.getLocation().add(0.5, 0.6, 0.5);
            w.playSound(c, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1.0f);
            w.spawnParticle(Particle.SMOKE, c, 14, 0.2, 0.2, 0.2, 0.02);
            for (Player near : w.getPlayers()) {
                if (near.getLocation().distanceSquared(c) <= 64.0) {
                    near.sendActionBar(Component.text("Багаття згасло. Додай паличок чи вугілля.", NamedTextColor.RED));
                }
            }
        } else {
            writeFuel(b, fuel);
        }
    }

    // ---------- паливо в блоці ----------

    /** Паливо в пам'яті сервера: переживає запалення, якщо PDC на блоці втрачається. */
    private final java.util.Map<String, Integer> fuelCache = new java.util.HashMap<>();

    private static String cacheKey(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private Integer readStored(Block b) {
        Integer cached = fuelCache.get(cacheKey(b));
        if (cached != null) {
            return cached;
        }
        if (b.getState(false) instanceof TileState ts) {
            Integer v = ts.getPersistentDataContainer().get(fuelKey, PersistentDataType.INTEGER);
            if (v != null) {
                fuelCache.put(cacheKey(b), v);
            }
            return v;
        }
        return null;
    }

    private int readFuel(Block b) {
        Integer v = readStored(b);
        return v == null ? 0 : v;
    }

    /** Завжди беремо свіжий знімок стану, щоб не перезаписати вже змінений lit. */
    private void writeFuel(Block b, int seconds) {
        if (seconds <= 0 && plugin.getConfig().getBoolean("campfire.debug", false)) {
            StringBuilder sb = new StringBuilder("[campfire] writeFuel(0) at " + b.getX() + "," + b.getY() + "," + b.getZ() + " from:");
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 2; i < Math.min(st.length, 9); i++) {
                sb.append(" <- ").append(st[i].getClassName().replaceAll(".*\\.", "")).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
            }
            plugin.getLogger().info(sb.toString());
        }
        fuelCache.put(cacheKey(b), Math.max(0, seconds));
        // TileState.update() перезаписує блок даними зі знімка, тож запам'ятовуємо живий стан
        // (запалене/погашене) і повертаємо його, якщо знімок його змінив.
        BlockData live = b.getBlockData();
        if (b.getState() instanceof TileState ts) {
            ts.getPersistentDataContainer().set(fuelKey, PersistentDataType.INTEGER, Math.max(0, seconds));
            ts.update(true, false);
        }
        if (!b.getBlockData().equals(live)) {
            b.setBlockData(live, false);
        }
    }
}
