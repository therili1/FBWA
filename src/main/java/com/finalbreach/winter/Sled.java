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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
 * Санки: 2 залізні злитки + човен. По снігу і льоду ковзають майже без тертя.
 *  - ЛКМ по санках — підштовхнути (навіть коли хтось сидить).
 *  - ПКМ по санках — сісти. Сидячи санки повертаються за поглядом, але не штовхаються і не керуються.
 *  - Присідання + ПКМ (без пасажира) — підібрати.
 * На спуску санки прискорюються вперед. Видно модель санок (ItemDisplay), сама стійка невидима.
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
    /** Модель санок, яка їде за стійкою. */
    private final Map<UUID, ItemDisplay> visuals = new HashMap<>();

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

    private boolean isSledStand(Entity e) {
        return e instanceof ArmorStand && e.getPersistentDataContainer().has(sledKey, PersistentDataType.BYTE);
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

        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.getPersistentDataContainer().set(sledKey, PersistentDataType.BYTE, (byte) 1);
        stand.setVisible(false);       // стійку не малюємо: видно модель санок
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setRotation(p.getLocation().getYaw(), 0F);
        stand.setPersistent(false);

        ItemDisplay visual = loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(sledItem());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            // модель санок лежить від 0 до 8 пікселів: піднімаємо її, щоб стояла на поверхні
            d.setTransformation(new Transformation(
                    new Vector3f(0, 0.5F, 0), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
            d.setPersistent(false);
        });
        visuals.put(stand.getUniqueId(), visual);
        lastY.put(stand.getUniqueId(), stand.getLocation().getY());

        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            p.getInventory().setItemInMainHand(held);
        } else {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
    }

    // ---------- взаємодія ----------

    /** ЛКМ по санках: підштовхуємо (навіть із пасажиром). Удар по санках не завдає шкоди ніколи. */
    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (!isSledStand(e.getEntity())) {
            return;
        }
        e.setCancelled(true);
        ArmorStand sled = (ArmorStand) e.getEntity();
        if (e.getDamager() instanceof Player p) {
            push(sled, p); // штовхати можна і тоді, коли на санках хтось сидить
        }
    }

    private void push(ArmorStand sled, Player p) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) {
            return;
        }
        Vector push = dir.normalize().multiply(PUSH);
        Vector v = sled.getVelocity();
        sled.setVelocity(new Vector(push.getX(), v.getY(), push.getZ()));
    }

    /** ПКМ по санках: сідаємо; присідання + ПКМ без пасажира: підбираємо. */
    @EventHandler
    public void onRightClick(PlayerInteractEntityEvent e) {
        handleRightClick(e.getPlayer(), e.getRightClicked(), e);
    }

    @EventHandler
    public void onRightClickAt(PlayerInteractAtEntityEvent e) {
        handleRightClick(e.getPlayer(), e.getRightClicked(), e);
    }

    private void handleRightClick(Player p, Entity entity, org.bukkit.event.Cancellable cancel) {
        if (!isSledStand(entity)) {
            return;
        }
        cancel.setCancelled(true);
        ArmorStand sled = (ArmorStand) entity;
        if (!sled.getPassengers().isEmpty()) {
            return; // вже хтось сидить
        }
        if (p.isSneaking()) {
            pickUp(p, sled);
        } else {
            sled.addPassenger(p);
            p.sendActionBar(Component.text("Сидиш на санках. Щоб злізти — присядь", NamedTextColor.GRAY));
        }
    }

    private void pickUp(Player p, ArmorStand sled) {
        removeAll(sled);
        p.getInventory().addItem(sledItem()).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendActionBar(Component.text("Санки підібрано", NamedTextColor.GRAY));
    }

    /** Прибирає стійку і модель. */
    private void removeAll(ArmorStand sled) {
        UUID id = sled.getUniqueId();
        lastY.remove(id);
        ItemDisplay v = visuals.remove(id);
        if (v != null) {
            v.remove();
        }
        sled.eject();
        sled.remove();
    }

    // ---------- фізика ----------

    /** Кожен тік: ковзання по снігу і льоду, прискорення на спуску; сидячий повертає санки за поглядом. */
    public void tick() {
        Iterator<Map.Entry<UUID, Double>> it = lastY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Double> entry = it.next();
            Entity e = Bukkit.getEntity(entry.getKey());
            if (!(e instanceof ArmorStand sled) || !sled.isValid()) {
                it.remove();
                ItemDisplay gone = visuals.remove(entry.getKey());
                if (gone != null) {
                    gone.remove();
                }
                continue;
            }
            Location bl = sled.getLocation();
            ItemDisplay vis = visuals.get(entry.getKey());
            if (vis != null && vis.isValid()) {
                vis.teleport(bl);
            }

            // сидячий повертає санки за своїм поглядом, без поштовху
            if (!sled.getPassengers().isEmpty() && sled.getPassengers().get(0) instanceof Player rider) {
                float yaw = rider.getLocation().getYaw();
                sled.setRotation(yaw, 0F);
                if (vis != null && vis.isValid()) {
                    vis.setRotation(yaw, 0F);
                }
            } else if (vis != null && vis.isValid()) {
                vis.setRotation(bl.getYaw(), 0F);
            }

            if (!onSnow(bl)) {
                entry.setValue(bl.getY());
                continue; // на інших поверхнях санки просто стоять
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
