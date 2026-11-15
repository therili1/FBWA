package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Keyed;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.bukkit.Registry;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Напої: казан над багаттям (варіння за рецептами з конфігу), термос на порції,
 * сирий шоколад, що нагрівається на печах. Температура в PDC предмета (0..100).
 */
public final class Drinks implements Listener {

    private static final class Recipe2 {
        final String type;
        final String name;
        final List<Material> ingredients;
        final double power;
        final int seconds;

        Recipe2(String type, String name, List<Material> ingredients, double power, int seconds) {
            this.type = type;
            this.name = name;
            this.ingredients = ingredients;
            this.power = power;
            this.seconds = seconds;
        }
    }

    private static final class Brew {
        final Location loc;
        final List<Material> items = new ArrayList<>();
        double progress;

        Brew(Location loc) {
            this.loc = loc;
        }
    }

    private final WinterPlugin plugin;
    private final NamespacedKey kType;
    private final NamespacedKey kTemp;
    private final NamespacedKey kThermos;
    private final NamespacedKey kSips;
    private final NamespacedKey kChampagneFill;
    private final NamespacedKey kRawRecipe;
    private final NamespacedKey kThermosRecipe;
    private final NamespacedKey kFillRecipe;

    private final Map<String, Recipe2> recipes = new LinkedHashMap<>();

    /** Біом -> {ідентифікатор досягнення, критерій}. Видає плагін, коли гравець заходить у біом. */
    private static final Map<String, String[]> BIOME_AWARDS = Map.of(
            "minecraft:snowy_plains", new String[]{"snowy_plains", "arrived"},
            "minecraft:snowy_taiga", new String[]{"white_bear", "done"},
            "minecraft:snowy_slopes", new String[]{"ice_climber", "done"},
            "minecraft:ice_spikes", new String[]{"ice_spikes_fan", "done"},
            "minecraft:frozen_peaks", new String[]{"frozen_peaks_win", "done"},
            "minecraft:frozen_ocean", new String[]{"frozen_ocean_dip", "done"},
            "minecraft:snowy_beach", new String[]{"snowy_beach_walk", "done"});
    private final Map<String, Brew> brews = new HashMap<>();
    private final Map<UUID, BukkitTask> satiation = new HashMap<>();

    public Drinks(WinterPlugin plugin) {
        this.plugin = plugin;
        this.kType = new NamespacedKey(plugin, "drink_type");
        this.kTemp = new NamespacedKey(plugin, "drink_temp");
        this.kThermos = new NamespacedKey(plugin, "thermos");
        this.kSips = new NamespacedKey(plugin, "thermos_sips");
        this.kChampagneFill = new NamespacedKey(plugin, "champagne_fill");
        this.kRawRecipe = new NamespacedKey(plugin, "raw_hot_chocolate");
        this.kThermosRecipe = new NamespacedKey(plugin, "thermos_empty");
        this.kFillRecipe = new NamespacedKey(plugin, "thermos_fill");
    }

    public void register() {
        loadRecipes();
        Bukkit.addRecipe(rawRecipe());
        Bukkit.addRecipe(thermosRecipe());
        Bukkit.addRecipe(fillRecipe());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void loadRecipes() {
        recipes.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("drinks.recipes");
        if (sec == null) {
            return;
        }
        for (String type : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(type);
            if (r == null) {
                continue;
            }
            List<Material> ing = new ArrayList<>();
            for (String m : r.getStringList("ingredients")) {
                Material mat = Material.matchMaterial(m);
                if (mat != null) {
                    ing.add(mat);
                }
            }
            if (ing.isEmpty()) {
                continue;
            }
            recipes.put(type, new Recipe2(type, r.getString("name", type), ing,
                    r.getDouble("power", 1.0), r.getInt("seconds", 180)));
        }
    }

    private int sipsMax() {
        return Math.max(1, plugin.getConfig().getInt("drinks.sips", 4));
    }

    /** Нижче цієї температури напій уже не дає ефектів (тепло й ситість). */
    private int minEffectTemp() {
        return plugin.getConfig().getInt("drinks.min-effect-temp", 40);
    }

    private int fillMinTemp() {
        return plugin.getConfig().getInt("drinks.fill-min-temp", 40);
    }

    // ---------- рецепти крафту ----------

    private ShapelessRecipe rawRecipe() {
        ShapelessRecipe r = new ShapelessRecipe(kRawRecipe, makeDrink("hot_chocolate", 0));
        r.addIngredient(Material.MILK_BUCKET);
        r.addIngredient(Material.COCOA_BEANS);
        r.addIngredient(Material.COCOA_BEANS);
        r.addIngredient(Material.SUGAR);
        return r;
    }

    private ShapedRecipe thermosRecipe() {
        ShapedRecipe r = new ShapedRecipe(kThermosRecipe, emptyThermos());
        r.shape("IGI", "I I", " I ");
        r.setIngredient('I', Material.IRON_INGOT);
        r.setIngredient('G', Material.GLASS);
        return r;
    }

    private ShapelessRecipe fillRecipe() {
        ShapelessRecipe r = new ShapelessRecipe(kFillRecipe, emptyThermos());
        r.addIngredient(Material.FLOWER_POT);
        r.addIngredient(Material.HONEY_BOTTLE);
        return r;
    }

    // ---------- предмети ----------

    /** Напої з власною моделлю і текстурою (CMD 1101..1105); інші типи лишаються на загальному кухлі 1001. */
    static final List<String> DRINK_MODELS = List.of("hot_chocolate", "herbal_tea", "ginger_tea", "mulled_wine", "coffee");

    static int drinkModelData(String type) {
        int i = DRINK_MODELS.indexOf(type);
        return i < 0 ? 1001 : 1101 + i;
    }

    public ItemStack makeDrink(String type, int temp) {
        ItemStack it = new ItemStack(Material.HONEY_BOTTLE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(displayName(type), NamedTextColor.GOLD));
        m.setCustomModelData(drinkModelData(type));
        PersistentDataContainer c = m.getPersistentDataContainer();
        c.set(kType, PersistentDataType.STRING, type);
        c.set(kTemp, PersistentDataType.INTEGER, temp);
        it.setItemMeta(m);
        refreshLore(it);
        return it;
    }

    public ItemStack emptyThermos() {
        ItemStack it = new ItemStack(Material.FLOWER_POT);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Термос", NamedTextColor.AQUA));
        m.setCustomModelData(1003);
        m.getPersistentDataContainer().set(kThermos, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return thermosStyle(it);
    }

    private ItemStack filledThermos(ItemStack source) {
        ItemStack it = emptyThermos();
        String type = source.getItemMeta().getPersistentDataContainer().get(kType, PersistentDataType.STRING);
        int temp = getTemp(source);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Термос: " + displayName(type), NamedTextColor.AQUA));
        m.setCustomModelData(1003);
        PersistentDataContainer c = m.getPersistentDataContainer();
        c.set(kType, PersistentDataType.STRING, type);
        c.set(kTemp, PersistentDataType.INTEGER, temp);
        c.set(kSips, PersistentDataType.INTEGER, sipsMax());
        it.setItemMeta(m);
        refreshLore(it);
        return thermosStyle(it);
    }

    private String displayName(String type) {
        Recipe2 r = recipes.get(type);
        if (r != null) {
            return r.name;
        }
        return "hot_chocolate".equals(type) ? "Гарячий шоколад" : type;
    }

    public boolean isDrink(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(kType, PersistentDataType.STRING);
    }

    public boolean isThermos(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(kThermos, PersistentDataType.BYTE);
    }

    public int getTemp(ItemStack it) {
        Integer v = it.getItemMeta().getPersistentDataContainer().get(kTemp, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private int getSips(ItemStack it) {
        Integer v = it.getItemMeta().getPersistentDataContainer().get(kSips, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private void setTemp(ItemStack it, int temp) {
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(kTemp, PersistentDataType.INTEGER, temp);
        it.setItemMeta(m);
        refreshLore(it);
    }

    private void refreshLore(ItemStack it) {
        if (!isDrink(it)) {
            return;
        }
        ItemMeta m = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Температура: " + getTemp(it) + "°C", NamedTextColor.GRAY));
        if (isThermos(it)) {
            lore.add(Component.text("Ковтків: " + getSips(it) + "/" + sipsMax(), NamedTextColor.GRAY));
        }
        m.lore(lore);
        it.setItemMeta(m);
    }

    // ---------- казан: варіння над багаттям ----------

    private boolean isIngredient(Material mat) {
        for (Recipe2 r : recipes.values()) {
            if (r.ingredients.contains(mat)) {
                return true;
            }
        }
        return false;
    }

    private Recipe2 matchRecipe(List<Material> items) {
        List<String> have = items.stream().map(Enum::name).sorted().toList();
        for (Recipe2 r : recipes.values()) {
            List<String> need = r.ingredients.stream().map(Enum::name).sorted().toList();
            if (need.equals(have)) {
                return r;
            }
        }
        return null;
    }

    private static boolean fireBelow(Block cauldron) {
        Block below = cauldron.getRelative(0, -1, 0);
        Material m = below.getType();
        return (m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE)
                && below.getBlockData() instanceof Lightable l && l.isLit();
    }

    @EventHandler
    public void onCauldronClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.CAULDRON) {
            return;
        }
        ItemStack hand = e.getItem();
        if (hand == null || hand.getType().isAir() || isDrink(hand) || isThermos(hand)) {
            return;
        }
        if (!isIngredient(hand.getType())) {
            return;
        }
        Player p = e.getPlayer();
        e.setCancelled(true);
        String key = locKey(b.getLocation());
        Brew brew = brews.computeIfAbsent(key, k -> new Brew(b.getLocation()));
        int max = plugin.getConfig().getInt("drinks.max-ingredients", 4);
        if (brew.items.size() >= max) {
            p.sendActionBar(Component.text("Казан повний: " + max + "/" + max, NamedTextColor.RED));
            return;
        }
        brew.items.add(hand.getType());
        consumeOne(p, EquipmentSlot.HAND);
        p.sendActionBar(Component.text("В казані: " + brew.items.size() + "/" + max, NamedTextColor.GOLD));
    }

    private static String locKey(Location l) {
        return l.getWorld().getUID() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    private void brewTick() {
        int brewSeconds = plugin.getConfig().getInt("drinks.brew-seconds", 20);
        Iterator<Brew> it = brews.values().iterator();
        while (it.hasNext()) {
            Brew brew = it.next();
            Block b = brew.loc.getBlock();
            if (b.getType() != Material.CAULDRON) {
                dropItems(brew);
                it.remove();
                continue;
            }
            if (!fireBelow(b)) {
                continue; // без вогню не варимо
            }
            brew.progress += 2;
            if (brew.progress < brewSeconds) {
                continue;
            }
            Recipe2 r = matchRecipe(brew.items);
            Location drop = brew.loc.clone().add(0.5, 1.0, 0.5);
            if (r != null) {
                brew.loc.getWorld().dropItemNaturally(drop, makeDrink(r.type, 90));
            } else {
                dropItems(brew);
            }
            it.remove();
        }
    }

    private void dropItems(Brew brew) {
        Location drop = brew.loc.clone().add(0.5, 1.0, 0.5);
        for (Material m : brew.items) {
            brew.loc.getWorld().dropItemNaturally(drop, new ItemStack(m));
        }
        brew.items.clear();
    }

    /** Досягнення за біомами: перевіряємо раз на кілька секунд у гравців у керованих світах. */
    private void biomeAwards() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.isManaged(p.getWorld())) {
                continue;
            }
            NamespacedKey key = Registry.BIOME.getKey(p.getLocation().getBlock().getBiome());
            if (key == null) {
                continue;
            }
            String[] adv = BIOME_AWARDS.get(key.toString());
            if (adv != null) {
                award(p, adv[0], adv[1]);
            }
        }
    }

    /** Звичайні напої в інвентарі повільно остигають (термос тримає тепло і не остигає). */
    private void coolInventories() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.isManaged(p.getWorld())) {
                continue;
            }
            ItemStack[] contents = p.getInventory().getContents();
            boolean changed = false;
            for (ItemStack st : contents) {
                if (st == null || !isDrink(st) || isThermos(st)) {
                    continue;
                }
                int t = getTemp(st);
                if (t > 0) {
                    setTemp(st, t - 1);
                    changed = true;
                }
            }
            if (changed) {
                p.getInventory().setContents(contents);
            }
        }
    }

    // ---------- підігрів сирого напою на землі ----------

    /** Викликається кожні 2 секунди: варіння в казані і нагрівання напоїв на землі. */
    public void tick() {
        brewTick();
        biomeAwards();
        coolInventories();
        Set<UUID> seen = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (!plugin.isManaged(w)) {
                continue;
            }
            for (Entity e : w.getNearbyEntities(p.getLocation(), 24, 24, 24)) {
                if (!(e instanceof Item item) || !seen.add(item.getUniqueId())) {
                    continue;
                }
                ItemStack st = item.getItemStack();
                if (!isDrink(st)) {
                    continue;
                }
                int t = getTemp(st);
                Block below = item.getLocation().getBlock().getRelative(0, -1, 0);
                int nt = heating(below) ? Math.min(100, t + 20) : Math.max(0, t - 1);
                if (nt != t) {
                    setTemp(st, nt);
                    item.setItemStack(st);
                }
            }
        }
    }

    private boolean heating(Block b) {
        Material m = b.getType();
        if (m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE) {
            return b.getBlockData() instanceof Lightable l && l.isLit();
        }
        if (m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER) {
            BlockState s = b.getState();
            return s instanceof org.bukkit.block.Furnace f && f.getBurnTime() > 0;
        }
        return false;
    }

    // ---------- пиття ----------

    /** Ефект напою: сила і тривалість з рецепту, сила масштабується температурою. */
    private void effect(Player p, ItemStack it) {
        String type = it.getItemMeta().getPersistentDataContainer().get(kType, PersistentDataType.STRING);
        if ("champagne".equals(type)) {
            // шампанське холодне: без тепла, зате короткий приріст швидкості
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 20, 0, false, false, true));
            return;
        }
        int t = getTemp(it);
        if (t < minEffectTemp()) {
            p.sendActionBar(Component.text("Напій остиг, ефекту немає", NamedTextColor.GRAY));
            return;
        }
        Recipe2 r = type == null ? null : recipes.get(type);
        double base = r != null ? r.power : 1.0;
        int seconds = r != null ? r.seconds : 180;
        double scale = 0.4 + 0.6 * t / 100.0;
        plugin.coldSystem().drink(p, seconds, base * scale);
        if ("hot_chocolate".equals(type)) {
            award(p, "hot_chocolate", "drank");
        }
        if ("coffee".equals(type)) {
            startSatiation(p);
        }
        if ("compote".equals(type)) {
            int lvl = Math.max(1, Math.min(5, plugin.getConfig().getInt("drinks.compote-saturation", 3)));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 1, lvl - 1, false, false, false));
        }
    }

    // ---------- шампанське: святковий бризок по блоку ----------

    private boolean isChampagne(ItemStack it) {
        if (it == null || !it.hasItemMeta()) {
            return false;
        }
        return "champagne".equals(it.getItemMeta().getPersistentDataContainer().get(kType, PersistentDataType.STRING));
    }

    /** Наповнення шампанського у відсотках: 100 = ціла пляшка, без спрею. */
    private int champagneFill(ItemStack it) {
        Integer f = it.getItemMeta().getPersistentDataContainer().get(kChampagneFill, PersistentDataType.INTEGER);
        return f == null ? 100 : f;
    }

    /** Удар (ЛКМ) по блоку з шампанським: бризок, пляшка втрачає половину і більше не розпилюється. */
    @EventHandler
    public void onChampagneSpray(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        ItemStack it = e.getItem();
        if (!isChampagne(it)) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (champagneFill(it) < 100) {
            p.sendActionBar(Component.text("Шампанське вже розпилене, тільки випити", NamedTextColor.GRAY));
            return;
        }
        int left = Math.max(0, plugin.getConfig().getInt("drinks.champagne.fill-after-spray", 50));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(kChampagneFill, PersistentDataType.INTEGER, left);
        it.setItemMeta(meta);
        p.getInventory().setItemInMainHand(it);
        spray(p);
    }

    /** Шампанське не ламає блок, коли ним б'ють по блоку. */
    @EventHandler
    public void onChampagneBlockDamage(BlockDamageEvent e) {
        if (isChampagne(e.getItemInHand())) {
            e.setCancelled(true);
        }
    }

    /** Бризок шампанського з очей гравця вперед; гравці поруч теж отримують бризки. */
    private void spray(Player p) {
        int ticks = Math.max(1, plugin.getConfig().getInt("drinks.champagne.spray-seconds", 2)) * 20;
        Particle.DustOptions gold = new Particle.DustOptions(Color.fromRGB(255, 215, 90), 1.2F);
        Particle.DustOptions white = new Particle.DustOptions(Color.fromRGB(255, 250, 230), 1.0F);
        p.getWorld().playSound(p.getEyeLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0F, 1.2F);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t >= ticks) {
                    cancel();
                    return;
                }
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection();
                for (int i = 0; i < 12; i++) {
                    double dist = 1.0 + rnd.nextDouble() * 4.0;
                    Location pos = eye.clone().add(dir.clone().multiply(dist));
                    pos.add((rnd.nextDouble() - 0.5) * 0.8, (rnd.nextDouble() - 0.5) * 0.8, (rnd.nextDouble() - 0.5) * 0.8);
                    p.getWorld().spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, i % 2 == 0 ? gold : white);
                }
                // гравці поруч "обливаються"
                for (Player other : p.getWorld().getPlayers()) {
                    if (other.equals(p) || other.getLocation().distanceSquared(p.getLocation()) > 36.0) {
                        continue;
                    }
                    other.getWorld().spawnParticle(Particle.DUST, other.getLocation().add(0, 1.2, 0), 6, 0.3, 0.4, 0.3, 0, gold);
                }
                if (t % 10 == 0) {
                    p.getWorld().playSound(eye, Sound.ENTITY_PLAYER_SPLASH, 0.6F, 1.4F);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Кава: ситість подається кожні кілька секунд протягом заданого часу. Новий ковток перезапускає таймер. */
    private void startSatiation(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask old = satiation.remove(id);
        if (old != null) {
            old.cancel();
        }
        int amp = Math.max(0, Math.min(5, plugin.getConfig().getInt("drinks.coffee-saturation.level", 5)) - 1);
        long interval = Math.max(1, plugin.getConfig().getInt("drinks.coffee-saturation.interval-seconds", 2)) * 20L;
        long duration = Math.max(1, plugin.getConfig().getInt("drinks.coffee-saturation.duration-seconds", 10)) * 20L;
        BukkitTask task = new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                if (!p.isOnline() || elapsed >= duration) {
                    cancel();
                    satiation.remove(id);
                    return;
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 1, amp, false, false, false));
                elapsed += interval;
            }
        }.runTaskTimer(plugin, 0L, interval);
        satiation.put(id, task);
    }

    @EventHandler
    public void onQuitSatiation(PlayerQuitEvent e) {
        BukkitTask t = satiation.remove(e.getPlayer().getUniqueId());
        if (t != null) {
            t.cancel();
        }
    }

    private void award(Player p, String advancementId, String criterion) {
        Advancement adv = Bukkit.getAdvancement(new NamespacedKey("fbwinter", advancementId));
        if (adv != null) {
            p.getAdvancementProgress(adv).awardCriteria(criterion);
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (isThermos(it)) {
            e.setCancelled(true); // ковток рахуємо самі, ванільне споживання не чіпає термос
            if (isDrink(it)) {
                sipThermos(e.getPlayer(), e.getHand(), it);
            }
            return;
        }
        if (!isDrink(it)) {
            return;
        }
        e.setCancelled(true); // ванільне пиття скасовуємо, ефект даємо самі
        Player p = e.getPlayer();
        effect(p, it);
        consumeOne(p, e.getHand());
        p.getInventory().addItem(new ItemStack(Material.GLASS_BOTTLE)).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void consumeOne(Player p, EquipmentSlot hand) {
        ItemStack held = hand == EquipmentSlot.OFF_HAND
                ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
        } else {
            held = new ItemStack(Material.AIR);
        }
        if (hand == EquipmentSlot.OFF_HAND) {
            p.getInventory().setItemInOffHand(held);
        } else {
            p.getInventory().setItemInMainHand(held);
        }
    }

    /** Термос: тримаєш ПКМ, як звичайний напій (ковток на завершенні). Блок-установка заборонена. */
    @EventHandler
    public void onThermosUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!isThermos(e.getItem())) {
            return;
        }
        e.setUseInteractedBlock(Event.Result.DENY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onThermosPlace(BlockPlaceEvent e) {
        if (isThermos(e.getItemInHand())) {
            e.setCancelled(true);
        }
    }

    /** Термос з вищими споживними властивостями, щоб його можна було тримати як їжу. */
    private ItemStack thermosStyle(ItemStack it) {
        it.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(1.6F)
                .animation(ItemUseAnimation.DRINK)
                .build());
        return it;
    }

    private void sipThermos(Player p, EquipmentSlot hand, ItemStack it) {
        effect(p, it);
        int left = getSips(it) - 1;
        ItemStack next;
        if (left <= 0) {
            next = emptyThermos();
        } else {
            ItemMeta m = it.getItemMeta();
            m.getPersistentDataContainer().set(kSips, PersistentDataType.INTEGER, left);
            it.setItemMeta(m);
            refreshLore(it);
            next = thermosStyle(it);
        }
        if (hand == EquipmentSlot.OFF_HAND) {
            p.getInventory().setItemInOffHand(next);
        } else {
            p.getInventory().setItemInMainHand(next);
        }
    }

    // ---------- наповнення термоса через крафт ----------

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        Recipe rec = e.getRecipe();
        if (!(rec instanceof Keyed k) || !k.getKey().equals(kFillRecipe)) {
            return;
        }
        ItemStack thermos = null;
        ItemStack drink = null;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it == null || it.getType().isAir()) {
                continue;
            }
            if (isThermos(it) && !isDrink(it)) {
                thermos = it;
            } else if (isDrink(it)) {
                drink = it;
            }
        }
        if (thermos == null || drink == null || getTemp(drink) < fillMinTemp()) {
            e.getInventory().setResult(null);
            return;
        }
        e.getInventory().setResult(filledThermos(drink));
    }
}
