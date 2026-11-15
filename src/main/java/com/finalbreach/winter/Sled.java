package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
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
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Санки: човен + 2 залізних злитки. По снігу і льоду ковзають майже без тертя, керувати не можна.
 * Спочатку їх треба підштовхнути (ЛКМ по санках), далі вони їдуть самі і на спуску прискорюються.
 * Присісти + ПКМ по санках — підібрати.
 *
 * Човен прихований (фізика), видно модель санок (ItemDisplay), а клікати можна по невидимому
 * Interaction, який стоїть на місці санок.
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
    /** CustomModelData санок у ресурс-паку (assets/minecraft/items/oak_boat.json). */
    public static final int SLED_MODEL = 2001;

    private final WinterPlugin plugin;
    private final NamespacedKey sledKey;
    private final NamespacedKey recipeKey;
    /** Попередня висота санок (для схилу). */
    private final Map<UUID, Double> lastY = new HashMap<>();
    /** Модель санок, яка їде за човном. */
    private final Map<UUID, ItemDisplay> visuals = new HashMap<>();
    /** Невидимий елемент для кліків: човен -> Interaction. */
    private final Map<UUID, Interaction> hitboxOf = new HashMap<>();
    /** Зворотний зв'язок: Interaction -> човен. */
    private final Map<UUID, UUID> boatOfHitbox = new HashMap<>();

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
        m.setCustomModelData(SLED_MODEL);
        m.getPersistentDataContainer().set(sledKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isSledItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(sledKey, PersistentDataType.BYTE);
    }

    private boolean isSledBoat(Entity e) {
        return e instanceof Boat && e.getPersistentDataContainer().has(sledKey, PersistentDataType.BYTE);
    }

    /** Санки, до яких відноситься сутність (сам човен або його невидимий Interaction). */
    private Boat sledOf(Entity e) {
        if (isSledBoat(e)) {
            return (Boat) e;
        }
        if (e instanceof Interaction) {
            UUID boatId = boatOfHitbox.get(e.getUniqueId());
            Entity boat = boatId == null ? null : Bukkit.getEntity(boatId);
            if (isSledBoat(boat)) {
                return (Boat) boat;
            }
        }
        return null;
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

    // ---------- установка ----------

    /** Поверхні, по яких санки їдуть: сніговий покрив, сніговий блок, порошковий сніг, лід. */
    private static boolean isSlidable(Material m) {
        return m == Material.SNOW || m == Material.SNOW_BLOCK || m == Material.POWDER_SNOW
                || m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE;
    }

    /** ПКМ по снігу або льоду з санками: ставимо їх на поверхню. */
    @EventHandler
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack hand = e.getItem();
        if (!isSledItem(hand)) {
            return;
        }
        Block surface = e.getClickedBlock();
        if (surface == null) {
            return;
        }
        if (!isSlidable(surface.getType()) && e.getBlockFace() == BlockFace.UP) {
            Block above = surface.getRelative(BlockFace.UP);
            if (isSlidable(above.getType())) {
                surface = above;
            }
        }
        if (!isSlidable(surface.getType())) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        // висота поверхні: для шару снігу — його товщина, для інших блоків — повний блок
        double top = surface.getType() == Material.SNOW
                ? ((Snow) surface.getBlockData()).getLayers() / 8.0 : 1.0;
        Location loc = surface.getLocation().add(0.5, top, 0.5);

        // Paper 26.3 не створює човни через клас Boat, тож беремо тип сутності
        Boat sled = (Boat) loc.getWorld().spawnEntity(loc, EntityType.OAK_BOAT);
        sled.getPersistentDataContainer().set(sledKey, PersistentDataType.BYTE, (byte) 1);
        sled.setRotation(p.getLocation().getYaw(), 0F);
        sled.setVisibleByDefault(false); // човен не малюємо: видно модель санок

        ItemDisplay visual = loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(sledItem());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            // модель санок лежить від 0 до 8 пікселів: піднімаємо її, щоб стояла на поверхні, а не під нею
            d.setTransformation(new Transformation(
                    new Vector3f(0, 0.5F, 0), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
            d.setPersistent(false);
        });
        visuals.put(sled.getUniqueId(), visual);

        Interaction hit = (Interaction) loc.getWorld().spawnEntity(loc, EntityType.INTERACTION);
        hit.setInteractionWidth(1.2F);
        hit.setInteractionHeight(0.7F);
        hit.setResponsive(true);
        hit.setPersistent(false);
        hitboxOf.put(sled.getUniqueId(), hit);
        boatOfHitbox.put(hit.getUniqueId(), sled.getUniqueId());

        lastY.put(sled.getUniqueId(), sled.getLocation().getY());

        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            p.getInventory().setItemInMainHand(held);
        } else {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
    }

    // ---------- взаємодія: штовхання і підбирання ----------

    /** ЛКМ по санках (або їх невидимому елементу): підштовхуємо, не ламаємо. */
    @EventHandler
    public void onPushEntity(EntityDamageByEntityEvent e) {
        Boat sled = sledOf(e.getEntity());
        if (sled == null || !(e.getDamager() instanceof Player p)) {
            return;
        }
        e.setCancelled(true);
        push(sled, p);
    }

    @EventHandler
    public void onPushVehicle(VehicleDamageEvent e) {
        if (isSledBoat(e.getVehicle()) && e.getAttacker() instanceof Player p) {
            e.setCancelled(true);
            push((Boat) e.getVehicle(), p);
        }
    }

    private void push(Boat sled, Player p) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) {
            return;
        }
        Vector push = dir.normalize().multiply(PUSH);
        Vector v = sled.getVelocity();
        sled.setVelocity(new Vector(push.getX(), v.getY(), push.getZ()));
    }

    /** Присідання + ПКМ по санках: забираємо їх у інвентар. */
    @EventHandler
    public void onPickUp(PlayerInteractEntityEvent e) {
        Boat sled = sledOf(e.getRightClicked());
        if (sled != null && e.getPlayer().isSneaking()) {
            e.setCancelled(true);
            pickUp(e.getPlayer(), sled);
        }
    }

    @EventHandler
    public void onPickUpAt(PlayerInteractAtEntityEvent e) {
        Boat sled = sledOf(e.getRightClicked());
        if (sled != null && e.getPlayer().isSneaking()) {
            e.setCancelled(true);
            pickUp(e.getPlayer(), sled);
        }
    }

    private void pickUp(Player p, Boat sled) {
        removeAll(sled);
        p.getInventory().addItem(sledItem()).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendActionBar(Component.text("Санки підібрано", NamedTextColor.GRAY));
    }

    /** Якщо човен знищили не гравцем, віддаємо предмет і прибираємо елементи. */
    @EventHandler
    public void onDestroy(VehicleDestroyEvent e) {
        if (!isSledBoat(e.getVehicle())) {
            return;
        }
        Location loc = e.getVehicle().getLocation();
        removeAll((Boat) e.getVehicle());
        loc.getWorld().dropItemNaturally(loc, sledItem());
    }

    /** Прибирає човен, модель і невидимий елемент. */
    private void removeAll(Boat sled) {
        UUID id = sled.getUniqueId();
        lastY.remove(id);
        ItemDisplay v = visuals.remove(id);
        if (v != null) {
            v.remove();
        }
        Interaction h = hitboxOf.remove(id);
        if (h != null) {
            boatOfHitbox.remove(h.getUniqueId());
            h.remove();
        }
        sled.remove();
    }

    // ---------- фізика ----------

    /** Кожен тік: ковзання по снігу і льоду, прискорення на спуску; модель і клікабельний елемент їдуть за човном. */
    public void tick() {
        Iterator<Map.Entry<UUID, Double>> it = lastY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Double> entry = it.next();
            Entity e = Bukkit.getEntity(entry.getKey());
            if (!(e instanceof Boat sled) || !sled.isValid()) {
                it.remove();
                ItemDisplay gone = visuals.remove(entry.getKey());
                if (gone != null) {
                    gone.remove();
                }
                Interaction h = hitboxOf.remove(entry.getKey());
                if (h != null) {
                    boatOfHitbox.remove(h.getUniqueId());
                    h.remove();
                }
                continue;
            }
            Location bl = sled.getLocation();
            ItemDisplay vis = visuals.get(entry.getKey());
            if (vis != null && vis.isValid()) {
                vis.teleport(bl);
                vis.setRotation(bl.getYaw(), 0F);
            }
            Interaction h = hitboxOf.get(entry.getKey());
            if (h != null && h.isValid()) {
                h.teleport(bl);
            }

            if (!onSnow(bl)) {
                entry.setValue(bl.getY());
                continue; // на інших поверхнях і у воді санки поводяться як звичайний човен
            }
            Vector v = sled.getVelocity();
            double dy = bl.getY() - entry.getValue();
            entry.setValue(bl.getY());

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
        return isSlidable(at.getType()) || isSlidable(below.getType());
    }
}
