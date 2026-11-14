package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Напої: сирий гарячий шоколад (нагрівається на запалених печах), багатогаразовий термос.
 * Температура напою в PDC предмета (0..100). На запаленій печі за ~10 с до 100, на землі повільно остигає.
 */
public final class Drinks implements Listener {

    /** Мінімальна температура, з якої напій можна налити в термос. */
    static final int HOT_MIN = 40;

    private final WinterPlugin plugin;
    private final NamespacedKey kType;
    private final NamespacedKey kTemp;
    private final NamespacedKey kThermos;
    private final NamespacedKey kRawRecipe;
    private final NamespacedKey kThermosRecipe;
    private final NamespacedKey kFillRecipe;

    public Drinks(WinterPlugin plugin) {
        this.plugin = plugin;
        this.kType = new NamespacedKey(plugin, "drink_type");
        this.kTemp = new NamespacedKey(plugin, "drink_temp");
        this.kThermos = new NamespacedKey(plugin, "thermos");
        this.kRawRecipe = new NamespacedKey(plugin, "raw_hot_chocolate");
        this.kThermosRecipe = new NamespacedKey(plugin, "thermos_empty");
        this.kFillRecipe = new NamespacedKey(plugin, "thermos_fill");
    }

    public void register() {
        Bukkit.addRecipe(rawRecipe());
        Bukkit.addRecipe(thermosRecipe());
        Bukkit.addRecipe(fillRecipe());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---------- рецепти ----------

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
        // Результат підставляється в PrepareItemCraftEvent залежно від температури напою
        ShapelessRecipe r = new ShapelessRecipe(kFillRecipe, emptyThermos());
        r.addIngredient(Material.FLOWER_POT);
        r.addIngredient(Material.HONEY_BOTTLE);
        return r;
    }

    // ---------- предмети ----------

    public ItemStack makeDrink(String type, int temp) {
        ItemStack it = new ItemStack(Material.HONEY_BOTTLE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(displayName(type), NamedTextColor.GOLD));
        m.setCustomModelData(1001);
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
        return it;
    }

    private ItemStack filledThermos(ItemStack source) {
        ItemStack it = emptyThermos();
        String type = source.getItemMeta().getPersistentDataContainer().get(kType, PersistentDataType.STRING);
        int temp = getTemp(source);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Термос: " + displayName(type), NamedTextColor.AQUA));
        PersistentDataContainer c = m.getPersistentDataContainer();
        c.set(kType, PersistentDataType.STRING, type);
        c.set(kTemp, PersistentDataType.INTEGER, temp);
        it.setItemMeta(m);
        refreshLore(it);
        return it;
    }

    private static String displayName(String type) {
        return switch (type) {
            case "hot_chocolate" -> "Гарячий шоколад";
            default -> type;
        };
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
        m.lore(List.of(Component.text("Температура: " + getTemp(it) + "°C", NamedTextColor.GRAY)));
        it.setItemMeta(m);
    }

    // ---------- підігрів і остигання ----------

    /** Викликається кожні 2 секунди: напої на запалених печах гріються, на землі повільно остигають. */
    public void tick() {
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

    /** Ефект напою: тепло в шарах (як шкіряна броня) залежно від температури і таймер. */
    private void effect(Player p, ItemStack it) {
        int t = getTemp(it);
        double power = 1.0 + 2.0 * t / 100.0;
        plugin.coldSystem().drink(p, 180, power);
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (!isDrink(it)) {
            return;
        }
        // Ванільне пиття скасовуємо: їсти/пити будемо самі, щоб не давало ванільного ефекту
        e.setCancelled(true);
        Player p = e.getPlayer();
        EquipmentSlot hand = e.getHand();
        effect(p, it);
        consumeOne(p, hand);
        p.getInventory().addItem(new ItemStack(Material.GLASS_BOTTLE)).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void consumeOne(Player p, EquipmentSlot hand) {
        ItemStack held = hand == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
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

    /** Термос: ПКМ п'є напій (термос лишається, стає порожнім). Порожній термос не ставиться як горщик. */
    @EventHandler
    public void onThermosUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack it = e.getItem();
        if (!isThermos(it)) {
            return;
        }
        e.setCancelled(true);
        if (!isDrink(it)) {
            return;
        }
        Player p = e.getPlayer();
        effect(p, it);
        p.getInventory().setItemInMainHand(emptyThermos());
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
        if (thermos == null || drink == null || getTemp(drink) < HOT_MIN) {
            e.getInventory().setResult(null);
            return;
        }
        e.getInventory().setResult(filledThermos(drink));
    }
}
