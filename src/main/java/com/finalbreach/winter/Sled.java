package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Санки: човен + 2 залізних злитки. По снігу ковзають як по льоду (майже без тертя),
 * керувати не можна. Спочатку їх треба підштовхнути (ЛКМ по санках), далі вони їдуть самі,
 * а на спуску прискорюються вперед. Пасажир бере їх ПКМ, зламати можна присіданням + ПКМ.
 */
public final class Sled implements Listener {

    /** Тертя по снігу: 1 = без тертя, менше = більше ковзання. */
    private static final double FRICTION = 0.985;
    /** Поштовх гравця (ЛКМ по санках). */
    private static final double PUSH = 0.6;
    /** Прискорення вперед на спуску за один тік. */
    private static final double DOWNHILL_ACCEL = 0.04;
    /** Максимальна швидкість санок. */
    private static final double MAX_SPEED = 1.2;
    /** Мінімальна швидкість, нижче якої санки зупиняються. */
    private static final double STOP_SPEED = 0.02;

    private final WinterPlugin plugin;
    private final NamespacedKey sledKey;
    private final NamespacedKey recipeKey;
    private final Map<UUID, Double> lastY = new HashMap<>();

    public Sled(WinterPlugin plugin) {
        this.plugin = plugin;
        this.sledKey = new NamespacedKey(plugin, "sled");
        this.recipeKey = new NamespacedKey(plugin, "sled_recipe");
    }

    // ---------- предмет і рецепт ----------

    public ItemStack sledItem() {
        ItemStack it = new ItemStack(Material.OAK_BOAT);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Санки", NamedTextColor.AQUA));
        m.getPersistentDataContainer().set(sledKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isSledItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(sledKey, PersistentDataType.BYTE);
    }

    private boolean isSled(Entity e) {
        return e instanceof Boat && e.getPersistentDataContainer().has(sledKey, PersistentDataType.BYTE);
    }

    /** Рецепт: будь-який човен + 2 залізні злитки. */
    public void registerRecipe() {
        List<Material> boats = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.name().endsWith("_BOAT")) {
                boats.add(m);
            }
        }
        ShapelessRecipe r = new ShapelessRecipe(recipeKey, sledItem());
        r.addIngredient(new RecipeChoice.MaterialChoice(boats));
        r.addIngredient(2, Material.IRON_INGOT);
        Bukkit.addRecipe(r);
    }

    // ---------- встановлення і керування ----------

    /** ПКМ по снігу з санками: ставимо їх на сніг. */
    @EventHandler
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack hand = e.getItem();
        if (!isSledItem(hand)) {
            return;
        }
        Block clicked = e.getClickedBlock();
        if (clicked == null || e.getBlockFace() == null) {
            return;
        }
        Block target = clicked.getRelative(e.getBlockFace());
        Material under = clicked.getType();
        if (under != Material.SNOW && under != Material.SNOW_BLOCK) {
            return; // санки тільки на сніг
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        Location loc = target.getLocation().add(0.5, 0.0, 0.5);
        Boat sled = loc.getWorld().spawn(loc, Boat.class, b -> {
            b.getPersistentDataContainer().set(sledKey, PersistentDataType.BYTE, (byte) 1);
            b.setRotation(p.getLocation().getYaw(), 0F);
        });
        lastY.put(sled.getUniqueId(), sled.getLocation().getY());
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
        } else {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
    }

    /** ЛКМ по санках: підштовхуємо їх у напрямку погляду (не ламаємо). */
    @EventHandler
    public void onPush(VehicleDamageEvent e) {
        if (!isSled(e.getVehicle()) || !(e.getAttacker() instanceof Player p)) {
            return;
        }
        e.setCancelled(true);
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) {
            return;
        }
        Vector push = dir.normalize().multiply(PUSH);
        Vector v = e.getVehicle().getVelocity();
        e.getVehicle().setVelocity(new Vector(push.getX(), v.getY(), push.getZ()));
    }

    /** Присідання + ПКМ по санках: забираємо їх у інвентар. */
    @EventHandler
    public void onPickUp(PlayerInteractEntityEvent e) {
        if (!isSled(e.getRightClicked()) || !e.getPlayer().isSneaking()) {
            return;
        }
        e.setCancelled(true);
        Boat sled = (Boat) e.getRightClicked();
        lastY.remove(sled.getUniqueId());
        sled.remove();
        e.getPlayer().getInventory().addItem(sledItem()).values()
                .forEach(left -> e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(), left));
        e.getPlayer().sendActionBar(Component.text("Санки підібрано", NamedTextColor.GRAY));
    }

    /** Якщо санки зникли не через гравця (наприклад, зламані), віддаємо предмет. */
    @EventHandler
    public void onDestroy(VehicleDestroyEvent e) {
        if (!isSled(e.getVehicle())) {
            return;
        }
        lastY.remove(e.getVehicle().getUniqueId());
        Location loc = e.getVehicle().getLocation();
        loc.getWorld().dropItemNaturally(loc, sledItem());
    }

    // ---------- фізика ----------

    /** Кожен тік (з головного циклу): ковзання по снігу, прискорення на спуску. */
    public void tick() {
        Iterator<Map.Entry<UUID, Double>> it = lastY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Double> entry = it.next();
            Entity e = Bukkit.getEntity(entry.getKey());
            if (!(e instanceof Boat sled) || !sled.isValid()) {
                it.remove();
                continue;
            }
            if (!onSnow(sled.getLocation())) {
                entry.setValue(sled.getLocation().getY());
                continue; // на інших поверхнях і у воді санки поводяться як звичайний човен
            }
            Vector v = sled.getVelocity();
            double dy = sled.getLocation().getY() - entry.getValue();
            entry.setValue(sled.getLocation().getY());

            double vx = v.getX() * FRICTION;
            double vz = v.getZ() * FRICTION;
            double hs = Math.sqrt(vx * vx + vz * vz);
            if (dy < -0.02 && hs > 0.001) {
                // з гори: прискорюємося вперед, за напрямком руху
                vx += vx / hs * DOWNHILL_ACCEL;
                vz += vz / hs * DOWNHILL_ACCEL;
                hs = Math.sqrt(vx * vx + vz * vz);
            }
            if (hs > MAX_SPEED) {
                vx *= MAX_SPEED / hs;
                vz *= MAX_SPEED / hs;
                hs = MAX_SPEED;
            }
            if (hs < STOP_SPEED) {
                vx = 0;
                vz = 0;
            }
            sled.setVelocity(new Vector(vx, v.getY(), vz));
        }
    }

    private boolean onSnow(Location loc) {
        Block at = loc.getBlock();
        Block below = at.getRelative(BlockFace.DOWN);
        Material a = at.getType();
        Material b = below.getType();
        return a == Material.SNOW || a == Material.SNOW_BLOCK || b == Material.SNOW_BLOCK || b == Material.SNOW;
    }
}
