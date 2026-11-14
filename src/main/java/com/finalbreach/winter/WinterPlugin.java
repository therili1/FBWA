package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WinterPlugin extends JavaPlugin implements Listener {

    private Settings settings;
    private Heaters heaters;
    private HeatField heat;
    private IceSystem ice;
    private ColdSystem cold;
    private Drinks drinks;
    private SnowMarks marks;
    private SnowSystem snow;
    private CampfireSystem campfires;
    private NamespacedKey recipeKey;
    private NamespacedKey magmaRecipeKey;

    private File changesFile;
    private YamlConfiguration changesCfg;

    private final Map<UUID, Climate> climates = new HashMap<>();
    private final Map<UUID, Long> lastSlot = new HashMap<>();
    private Double overrideTemp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), getLogger());
        changesFile = new File(getDataFolder(), "changes.yml");
        changesCfg = YamlConfiguration.loadConfiguration(changesFile);

        heaters = new Heaters(this);
        heat = new HeatField(this);
        ice = new IceSystem(this, heat);
        cold = new ColdSystem(this, heaters, heat);
        marks = new SnowMarks(this);
        snow = new SnowSystem(this, marks);
        campfires = new CampfireSystem(this);

        registerRecipe();
        drinks = new Drinks(this);
        drinks.register();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(campfires, this);
        getServer().getPluginManager().registerEvents(new WorldSnow(this), this);
        Whitelist whitelist = new Whitelist(this);
        getServer().getPluginManager().registerEvents(whitelist, this);
        PluginCommand wlCmd = getCommand("wl");
        if (wlCmd != null) {
            wlCmd.setExecutor(whitelist);
        }
        getServer().getPluginManager().registerEvents(new Greenhouse(this), this);

        PluginCommand cmd = getCommand("forecast");
        if (cmd != null) {
            ForecastCommand fc = new ForecastCommand(this);
            cmd.setExecutor(fc);
            cmd.setTabCompleter(fc);
        }

        getServer().getScheduler().runTaskTimer(this, this::tickWeather, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, heat, 20L, settings.heatEvery * 20L);
        getServer().getScheduler().runTaskTimer(this, cold, 25L, 10L);
        getServer().getScheduler().runTaskTimer(this, drinks::tick, 60L, 40L);
        getServer().getScheduler().runTaskTimer(this, campfires, 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, snow::tick, 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, snow::particles, 40L, 4L);
        getServer().getScheduler().runTaskTimer(this, ice::tick, 60L, 100L);
        getServer().getScheduler().runTaskTimer(this, marks::flushDirty, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        saveChanges();
        if (snow != null) {
            snow.resetAll();
        }
        if (marks != null) {
            marks.flushAll();
        }
        if (cold != null) {
            cold.forgetAll();
        }
        if (recipeKey != null) {
            Bukkit.removeRecipe(recipeKey);
        }
    }

    public void reloadAll() {
        saveChanges();
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        climates.clear();
        lastSlot.clear();
    }

    // ---------- доступ для інших класів ----------

    public ColdSystem coldSystem() {
        return cold;
    }

    public Settings settings() {
        return settings;
    }

    public HeatField heat() {
        return heat;
    }

    public boolean isManaged(World w) {
        return settings.worlds.contains(w.getName());
    }

    public Climate climate(World w) {
        return climates.computeIfAbsent(w.getUID(), k -> {
            Climate c = new Climate(w.getSeed(), settings);
            c.setChanges(loadChanges(w.getName()));
            return c;
        });
    }

    /** Діапазон температур для біому в цьому місці. */
    public Settings.Profile profile(Block b) {
        NamespacedKey key = Registry.BIOME.getKey(b.getBiome());
        return settings.profile(key == null ? "" : key.toString(), b.getTemperature());
    }

    public Settings.Profile profile(Location loc) {
        return profile(loc.getBlock());
    }

    /** Температура надворі в цій точці (з урахуванням біому й тестового override). */
    public double outsideTemp(Location loc) {
        if (overrideTemp != null) {
            return overrideTemp;
        }
        World w = loc.getWorld();
        return climate(w).temp(w.getFullTime(), profile(loc));
    }

    public Double overrideTemp() {
        return overrideTemp;
    }

    public void setOverrideTemp(Double t) {
        overrideTemp = t;
    }

    // ---------- адмінські зміни прогнозу: збереження ----------

    private List<Climate.Change> loadChanges(String worldName) {
        List<Climate.Change> out = new ArrayList<>();
        for (Map<?, ?> m : changesCfg.getMapList(worldName)) {
            try {
                long at = ((Number) m.get("at")).longValue();
                double off = ((Number) m.get("offset")).doubleValue();
                long until = ((Number) m.get("until")).longValue();
                Object p = m.get("precip");
                Climate.Precip precip = p == null ? null : Climate.Precip.valueOf(p.toString());
                out.add(new Climate.Change(at, off, precip, until));
            } catch (Exception ex) {
                getLogger().warning("changes.yml: пропускаю некоректний запис для світу " + worldName);
            }
        }
        return out;
    }

    public void saveChanges() {
        if (changesCfg == null) {
            return;
        }
        for (World w : Bukkit.getWorlds()) {
            Climate c = climates.get(w.getUID());
            if (c == null) {
                continue;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (Climate.Change ch : c.changes()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", ch.at());
                m.put("offset", ch.offset());
                if (ch.precip() != null) {
                    m.put("precip", ch.precip().name());
                }
                m.put("until", ch.until());
                list.add(m);
            }
            changesCfg.set(w.getName(), list.isEmpty() ? null : list);
        }
        try {
            getDataFolder().mkdirs();
            changesCfg.save(changesFile);
        } catch (IOException ex) {
            getLogger().warning("Не вдалося зберегти changes.yml: " + ex.getMessage());
        }
    }

    /** Викликається після /forecast change: зберегти й одразу застосувати нову погоду. */
    public void afterChange(World w) {
        saveChanges();
        lastSlot.remove(w.getUID());
    }

    // ---------- погода за розкладом ----------

    private void tickWeather() {
        if (!settings.controlWeather) {
            return;
        }
        for (World w : Bukkit.getWorlds()) {
            if (!isManaged(w)) {
                continue;
            }
            Climate.Entry e = climate(w).at(w.getFullTime());
            long key = e.day() * 100L + e.index();
            Long last = lastSlot.get(w.getUID());
            boolean wantStorm = e.precip() != Climate.Precip.CLEAR;
            boolean wantThunder = e.precip() == Climate.Precip.BLIZZARD;
            boolean changed = last == null || last != key;
            // ванільний цикл погоди може перемкнути її сам: тоді повертаємо як за прогнозом
            if (changed || w.hasStorm() != wantStorm || w.isThundering() != wantThunder) {
                lastSlot.put(w.getUID(), key);
                applyWeather(w, e);
            }
        }
    }

    private void applyWeather(World w, Climate.Entry e) {
        int tod = (int) Math.floorMod(w.getFullTime(), (long) Climate.DAY);
        int left = Math.max(40, e.toTick() - tod);
        switch (e.precip()) {
            case CLEAR -> {
                w.setStorm(false);
                w.setThundering(false);
                w.setClearWeatherDuration(left);
            }
            case SNOW -> {
                w.setStorm(true);
                w.setThundering(false);
                w.setWeatherDuration(left);
            }
            case BLIZZARD -> {
                w.setStorm(true);
                w.setThundering(true);
                w.setWeatherDuration(left);
                w.setThunderDuration(left);
            }
        }
    }

    // ---------- рецепт грілки ----------

    private void registerRecipe() {
        recipeKey = new NamespacedKey(this, "heater");
        magmaRecipeKey = new NamespacedKey(this, "heater_magma");

        ItemStack placeholder = new ItemStack(Material.IRON_CHESTPLATE);
        ItemMeta meta = placeholder.getItemMeta();
        meta.displayName(Component.text("Броня з грілкою", NamedTextColor.GOLD));
        placeholder.setItemMeta(meta);

        ShapedRecipe r = new ShapedRecipe(recipeKey, placeholder);
        r.shape("LCL", "CAC", "LCL");
        r.setIngredient('L', Material.LEATHER);
        r.setIngredient('C', Material.CHARCOAL);
        r.setIngredient('A', new RecipeChoice.MaterialChoice(new ArrayList<>(Heaters.heatableMaterials())));
        r.setCategory(CraftingBookCategory.EQUIPMENT);
        Bukkit.addRecipe(r);

        // Магматична грілка: магма замість вугілля по краях, довша тривалість
        ShapedRecipe mr = new ShapedRecipe(magmaRecipeKey, placeholder);
        mr.shape("LML", "MAM", "LML");
        mr.setIngredient('L', Material.LEATHER);
        mr.setIngredient('M', Material.MAGMA_BLOCK);
        mr.setIngredient('A', new RecipeChoice.MaterialChoice(new ArrayList<>(Heaters.heatableMaterials())));
        mr.setCategory(CraftingBookCategory.EQUIPMENT);
        Bukkit.addRecipe(mr);
    }

    private boolean isHeaterRecipe(Recipe r) {
        return r instanceof Keyed k && (k.getKey().equals(recipeKey) || k.getKey().equals(magmaRecipeKey));
    }

    private boolean isMagmaRecipe(Recipe r) {
        return r instanceof Keyed k && k.getKey().equals(magmaRecipeKey);
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!isHeaterRecipe(e.getRecipe())) {
            return;
        }
        ItemStack[] m = e.getInventory().getMatrix();
        ItemStack center = m.length > 4 ? m[4] : null;
        if (center == null || center.getType().isAir()) {
            e.getInventory().setResult(null);
            return;
        }
        boolean magma = isMagmaRecipe(e.getRecipe());
        e.getInventory().setResult(magma
                ? heaters.apply(center, settings.magmaHeaterSeconds, true)
                : heaters.apply(center, settings.heaterSeconds));
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (!isHeaterRecipe(e.getRecipe())) {
            return;
        }
        ItemStack res = e.getCurrentItem();
        if (res != null && heaters.has(res)) {
            return;
        }
        ItemStack[] m = e.getInventory().getMatrix();
        ItemStack center = m.length > 4 ? m[4] : null;
        if (center == null || center.getType().isAir()) {
            e.setCancelled(true);
            return;
        }
        boolean magma = isMagmaRecipe(e.getRecipe());
        e.setCurrentItem(magma
                ? heaters.apply(center, settings.magmaHeaterSeconds, true)
                : heaters.apply(center, settings.heaterSeconds));
    }

    // ---------- сніг гравців ----------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material t = e.getBlockPlaced().getType();
        if (t == Material.SNOW || t == Material.SNOW_BLOCK) {
            marks.setOurs(e.getBlockPlaced(), false);
            marks.setPlayer(e.getBlockPlaced(), true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Material t = e.getBlock().getType();
        if (t == Material.SNOW || t == Material.SNOW_BLOCK) {
            marks.clear(e.getBlock());
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        marks.flush(e.getChunk());
    }

    // ---------- гравці ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (settings.discoverRecipe) {
            e.getPlayer().discoverRecipe(recipeKey);
            e.getPlayer().discoverRecipe(magmaRecipeKey);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cold.forget(e.getPlayer());
        snow.forget(e.getPlayer());
        heat.forget(e.getPlayer().getUniqueId());
    }
}
