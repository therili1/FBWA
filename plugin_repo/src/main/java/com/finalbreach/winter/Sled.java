package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Санки: 1, 2 або 4 місця.
 *  - 1 місце: сидиш на самих санках.
 *  - 2 місця: 2 звичайні санки + 2 залізні злитки. 4 місця: 2 двомісні + 4 злитки.
 * Усі місця їдуть за одним тілом (стійка без видимості), фізика рахується один раз.
 *  - ЛКМ по санках — поштовх (сильніше з розбігу, слабше присівши), навіть коли хтось сидить.
 *  - ПКМ — сісти на вільне місце. Присідання + ПКМ без пасажирів — підібрати.
 *  - Сидячий повертає санки за поглядом, без поштовху.
 */
public final class Sled implements Listener, CommandExecutor {

    /** Тертя по снігу: 1 = без тертя, менше = більше ковзання. */
    private static final double FRICTION = 0.985;
    /** Поштовхи: звичайний, з розбігу, присівши. */
    private static final double PUSH = 0.6;
    private static final double PUSH_SPRINT = 1.2;
    private static final double PUSH_SNEAK = 0.25;
    /** Прискорення вперед на спуску за один тік. */
    private static final double DOWNHILL_ACCEL = 0.04;
    /** Максимальна швидкість санок. */
    private static final double MAX_SPEED = 1.2;
    /** Мінімальна швидкість, нижче якої санки зупиняються. */
    private static final double STOP_SPEED = 0.02;
    /** Найвища сходинка, на яку санки заїжджають (блоків). Вище — стіна. */
    private static final double STEP_UP = 1.1;
    /** Втрата швидкості на кожен блок підйому. */
    private static final double UPHILL_LOSS_PER_BLOCK = 0.25;
    /** Як далеко вперед дивимося, щоб помітити сходинку до зіткнення. */
    private static final double LOOKAHEAD = 1.0;
    /** Вертикальний імпульс на краю рампи: скільки вгору за кожну одиницю горизонтальної швидкості. */
    private static final double LAUNCH_FACTOR = 0.35;
    /** Додатковий розгін на краю перепаду. */
    private static final double EDGE_BOOST = 1.15;

    /** Зсуви місць (x, z) відносно тіла санок для 2 і 4 місць. */
    private static final double[][] OFFSETS_2 = {{-0.45, 0}, {0.45, 0}};
    /** Останні позиції санок для визначення напрямку руху. */
    private static final Map<UUID, org.bukkit.Location> LAST_POS = new HashMap<>();
    /** Мінімальний зсув за тік, щоб санки повернулися (квадрат відстані). */
    private static final double MIN_MOVE_SQ = 0.0025;
    /** Максимальний поворот санок за один тік, у градусах. */
    private static final float MAX_TURN_PER_TICK = 6F;
    private static final double[][] OFFSETS_4 = {{-0.45, -0.5}, {0.45, -0.5}, {-0.45, 0.5}, {0.45, 0.5}};

    private final WinterPlugin plugin;
    private final NamespacedKey sledKey;
    private final NamespacedKey seatsKey;
    private final NamespacedKey recipeKey1;
    private final NamespacedKey recipeKey2;
    private final NamespacedKey recipeKey4;
    /** Попередня висота тіла санок (для схилу). Ключ — UUID тіла. */
    private final Map<UUID, Double> lastY = new HashMap<>();
    /** Горизонтальна швидкість тіла (x, z). */
    private final Map<UUID, double[]> speed = new HashMap<>();
    /** Модель санок, яка їде за тілом. */
    private final Map<UUID, ItemDisplay> visuals = new HashMap<>();
    /** Невидимий Interaction для кліків: ширший за стійку. */
    private final Map<UUID, Interaction> hitbox = new HashMap<>();
    /** Interaction → тіло. */
    private final Map<UUID, UUID> bodyOfHitbox = new HashMap<>();
    /** Тіло → додаткові місця (для 2 і 4 місць). Для 1 місця список порожній: сидиш на тілі. */
    private final Map<UUID, List<UUID>> seatsOf = new HashMap<>();
    /** Місце → тіло. */
    private final Map<UUID, UUID> bodyOfSeat = new HashMap<>();
    /** Кількість місць тіла. */
    private final Map<UUID, Integer> seatCount = new HashMap<>();
    private boolean fallDamageOn = false;

    public Sled(WinterPlugin plugin) {
        this.plugin = plugin;
        this.sledKey = new NamespacedKey(plugin, "sled");
        this.seatsKey = new NamespacedKey(plugin, "sled_seats");
        this.recipeKey1 = new NamespacedKey(plugin, "sled_recipe");
        this.recipeKey2 = new NamespacedKey(plugin, "sled_recipe_2");
        this.recipeKey4 = new NamespacedKey(plugin, "sled_recipe_4");
    }

    // ---------- предмети і рецепти ----------

    /** Санки з заданою кількістю місць (1, 2 або 4). */
    public ItemStack sledItem(int seats) {
        ItemStack it = new ItemStack(Material.OAK_BOAT);
        ItemMeta m = it.getItemMeta();
        String name = seats == 1 ? "Санки" : seats == 2 ? "Двомісні санки" : seats == 4 ? "Чотиримісні санки" : "Санки на " + seats;
        m.displayName(Component.text(name, NamedTextColor.AQUA));
        m.setCustomModelData(seats == 1 ? 2001 : seats == 2 ? 2002 : 2003);
        m.getPersistentDataContainer().set(sledKey, PersistentDataType.BYTE, (byte) 1);
        m.getPersistentDataContainer().set(seatsKey, PersistentDataType.INTEGER, seats);
        it.setItemMeta(m);
        return it;
    }

    private boolean isSledItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(sledKey, PersistentDataType.BYTE);
    }

    private int seatsOfItem(ItemStack it) {
        Integer s = it.getItemMeta().getPersistentDataContainer().get(seatsKey, PersistentDataType.INTEGER);
        return s == null ? 1 : s;
    }

    private boolean isSledStand(Entity e) {
        return e instanceof ArmorStand && e.getPersistentDataContainer().has(sledKey, PersistentDataType.BYTE);
    }

    /** Рецепти: 1 місце — човен + 2 злитки; 2 місця — 2 санки + 2 злитки; 4 місця — 2 двомісні + 4 злитки. */
    public void registerRecipes() {
        List<Material> boats = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.name().endsWith("_BOAT")) {
                boats.add(m);
            }
        }
        ShapelessRecipe r1 = new ShapelessRecipe(recipeKey1, sledItem(1));
        r1.addIngredient(new RecipeChoice.MaterialChoice(boats));
        r1.addIngredient(2, Material.IRON_INGOT);
        Bukkit.addRecipe(r1);

        ShapelessRecipe r2 = new ShapelessRecipe(recipeKey2, sledItem(2));
        RecipeChoice sled1 = new RecipeChoice.ExactChoice(List.of(sledItem(1)));
        r2.addIngredient(sled1);
        r2.addIngredient(sled1);
        r2.addIngredient(2, Material.IRON_INGOT);
        Bukkit.addRecipe(r2);

        ShapelessRecipe r4 = new ShapelessRecipe(recipeKey4, sledItem(4));
        RecipeChoice sled2 = new RecipeChoice.ExactChoice(List.of(sledItem(2)));
        r4.addIngredient(sled2);
        r4.addIngredient(sled2);
        r4.addIngredient(4, Material.IRON_INGOT);
        Bukkit.addRecipe(r4);
    }

    // ---------- установка ----------

    private static boolean isSlidable(Material m) {
        return m == Material.SNOW || m == Material.SNOW_BLOCK || m == Material.POWDER_SNOW
                || m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE;
    }

    /** ПКМ з санками по блоку: ставимо на сніг або лід. На інші блоки не ставимо, човном не стають. */
    @EventHandler
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack hand = e.getItem();
        if (!isSledItem(hand)) {
            return;
        }
        e.setCancelled(true); // санки ніколи не стають човном, навіть на воді
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
        Player p = e.getPlayer();
        if (!isSlidable(surface.getType())) {
            p.sendActionBar(Component.text("Санки ставляться тільки на сніг або лід", NamedTextColor.RED));
            return;
        }
        int seats = seatsOfItem(hand);
        double top = surface.getType() == Material.SNOW
                ? ((Snow) surface.getBlockData()).getLayers() / 8.0 : 1.0;
        Location loc = surface.getLocation().add(0.5, top, 0.5);
        World w = loc.getWorld();

        ArmorStand body = (ArmorStand) w.spawnEntity(loc, EntityType.ARMOR_STAND);
        body.getPersistentDataContainer().set(sledKey, PersistentDataType.BYTE, (byte) 1);
        body.setVisible(false);
        body.setSmall(true);
        body.setBasePlate(false);
        body.setArms(false);
        body.setGravity(true);
        body.setRotation(p.getLocation().getYaw(), 0F);
        body.setPersistent(false);
        UUID bodyId = body.getUniqueId();
        seatCount.put(bodyId, seats);

        ItemDisplay visual = w.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(sledItem(seats));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(
                    new Vector3f(0, 0.5F, 0), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setPersistent(false);
        });
        visuals.put(bodyId, visual);

        Interaction box = (Interaction) w.spawnEntity(loc, EntityType.INTERACTION);
        box.setInteractionWidth(1.2F);
        box.setInteractionHeight(0.8F);
        box.setResponsive(true);
        box.setPersistent(false);
        hitbox.put(bodyId, box);
        bodyOfHitbox.put(box.getUniqueId(), bodyId);

        // додаткові місця: для 2 і 4 — окремі стійки, тіло само місцем не є
        List<UUID> extras = new ArrayList<>();
        double[][] offs = seats == 2 ? OFFSETS_2 : seats == 4 ? OFFSETS_4 : null;
        if (offs != null) {
            for (double[] o : offs) {
                ArmorStand seat = (ArmorStand) w.spawnEntity(seatLoc(loc, o, p.getLocation().getYaw()), EntityType.ARMOR_STAND);
                seat.getPersistentDataContainer().set(sledKey, PersistentDataType.BYTE, (byte) 1);
                seat.setVisible(false);
                seat.setSmall(true);
                seat.setBasePlate(false);
                seat.setArms(false);
                seat.setGravity(false);
                seat.setPersistent(false);
                extras.add(seat.getUniqueId());
                bodyOfSeat.put(seat.getUniqueId(), bodyId);
            }
        }
        seatsOf.put(bodyId, extras);
        lastY.put(bodyId, body.getLocation().getY());

        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            p.getInventory().setItemInMainHand(held);
        } else {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
    }

    /** Точка місця: зсув (ox, oz) повернутий разом з напрямком санок. */
    private static Location seatLoc(Location center, double[] o, float yaw) {
        double rad = Math.toRadians(-yaw);
        double x = o[0] * Math.cos(rad) - o[1] * Math.sin(rad);
        double z = o[0] * Math.sin(rad) + o[1] * Math.cos(rad);
        return new Location(center.getWorld(), center.getX() + x, center.getY(), center.getZ() + z, yaw, 0F);
    }

    // ---------- зв'язки сутностей ----------

    /** Тіло санок, до якого належить сутність (тіло, його місце або його Interaction). */
    private ArmorStand bodyOf(Entity e) {
        UUID id = e.getUniqueId();
        UUID body = null;
        if (e instanceof Interaction) {
            body = bodyOfHitbox.get(id);
        } else if (isSledStand(e)) {
            body = bodyOfSeat.getOrDefault(id, id);
        }
        if (body == null || !lastY.containsKey(body)) {
            return null;
        }
        Entity b = Bukkit.getEntity(body);
        return isSledStand(b) ? (ArmorStand) b : null;
    }

    /** Вільне місце тіла: для 1 місця — саме тіло, для 2–4 — вільна додаткова стійка. */
    private ArmorStand freeSeat(ArmorStand body) {
        List<UUID> extras = seatsOf.getOrDefault(body.getUniqueId(), List.of());
        if (extras.isEmpty()) {
            return body.getPassengers().isEmpty() ? body : null;
        }
        for (UUID id : extras) {
            Entity seat = Bukkit.getEntity(id);
            if (isSledStand(seat) && seat.getPassengers().isEmpty()) {
                return (ArmorStand) seat;
            }
        }
        return null;
    }

    private boolean anyPassenger(ArmorStand body) {
        if (!body.getPassengers().isEmpty()) {
            return true;
        }
        for (UUID id : seatsOf.getOrDefault(body.getUniqueId(), List.of())) {
            Entity seat = Bukkit.getEntity(id);
            if (seat != null && !seat.getPassengers().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---------- удар, штовхання ----------

    /** ЛКМ по санках (тіло, місце або Interaction): поштовх. Шкоди санкам немає. */
    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        ArmorStand body = bodyOf(e.getEntity());
        if (body == null) {
            return;
        }
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) {
            push(body, p, pushForce(p));
        }
    }

    /**
     * ЛКМ по Interaction: він не отримує шкоди, тому ловимо взмах руки і промінь з очей.
     * Удар по стійці чи місцю обробляє onHit, тут лише для Interaction (без подвійного поштовху).
     */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player p = e.getPlayer();
        RayTraceResult hit = p.getWorld().rayTraceEntities(
                p.getEyeLocation(), p.getEyeLocation().getDirection(), 4.0,
                en -> en instanceof Interaction && bodyOfHitbox.containsKey(en.getUniqueId()));
        if (hit == null || hit.getHitEntity() == null) {
            return;
        }
        ArmorStand body = bodyOf(hit.getHitEntity());
        if (body != null) {
            push(body, p, pushForce(p));
        }
    }

    private double pushForce(Player p) {
        return p.isSprinting() ? PUSH_SPRINT : (p.isSneaking() ? PUSH_SNEAK : PUSH);
    }

    private void push(ArmorStand body, Player p, double force) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) {
            return;
        }
        Vector push = dir.normalize().multiply(force);
        Vector v = body.getVelocity();
        body.setVelocity(new Vector(push.getX(), v.getY(), push.getZ()));
        speed.put(body.getUniqueId(), new double[]{push.getX(), push.getZ()});
    }

    // ---------- посадка і підбирання ----------

    @EventHandler
    public void onRightClick(PlayerInteractEntityEvent e) {
        handleRightClick(e.getPlayer(), e.getRightClicked(), e);
    }

    @EventHandler
    public void onRightClickAt(PlayerInteractAtEntityEvent e) {
        handleRightClick(e.getPlayer(), e.getRightClicked(), e);
    }

    private void handleRightClick(Player p, Entity entity, Cancellable cancel) {
        ArmorStand body = bodyOf(entity);
        if (body == null) {
            return;
        }
        cancel.setCancelled(true);
        if (p.isSneaking() && !anyPassenger(body)) {
            pickUp(p, body);
            return;
        }
        ArmorStand seat = freeSeat(body);
        if (seat == null) {
            p.sendActionBar(Component.text("Немає вільних місць", NamedTextColor.GRAY));
            return;
        }
        seat.addPassenger(p);
        p.sendActionBar(Component.text("Сидиш на санках. Щоб злізти — присядь", NamedTextColor.GRAY));
    }

    private void pickUp(Player p, ArmorStand body) {
        int seats = seatCount.getOrDefault(body.getUniqueId(), 1);
        removeAll(body);
        p.getInventory().addItem(sledItem(seats)).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendActionBar(Component.text("Санки підібрано", NamedTextColor.GRAY));
    }

    // ---------- прибирання ----------

    /** Прибирає тіло, місця, модель і Interaction. Пасажирів висаджує. */
    private void removeAll(ArmorStand body) {
        UUID id = body.getUniqueId();
        lastY.remove(id);
        speed.remove(id);
        seatCount.remove(id);
        ItemDisplay v = visuals.remove(id);
        if (v != null) {
            v.remove();
        }
        Interaction box = hitbox.remove(id);
        if (box != null) {
            bodyOfHitbox.remove(box.getUniqueId());
            box.remove();
        }
        for (UUID seatId : seatsOf.getOrDefault(id, List.of())) {
            Entity seat = Bukkit.getEntity(seatId);
            bodyOfSeat.remove(seatId);
            if (seat != null) {
                seat.eject();
                seat.remove();
            }
        }
        seatsOf.remove(id);
        body.eject();
        body.remove();
    }

    /** Фол-дамаг для сидячих: за замовчуванням вимкнений (команда /falldamage). */
    @EventHandler
    public void onFall(EntityDamageEvent e) {
        if (fallDamageOn || e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) {
            return;
        }
        if (p.getVehicle() != null && isSledStand(p.getVehicle())) {
            e.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text("Фол-дамаг санок: " + (fallDamageOn ? "увімкнений" : "вимкнений"),
                    NamedTextColor.GRAY));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                fallDamageOn = true;
                sender.sendMessage(Component.text("Фол-дамаг санок увімкнено.", NamedTextColor.GREEN));
            }
            case "off" -> {
                fallDamageOn = false;
                sender.sendMessage(Component.text("Фол-дамаг санок вимкнено.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Використання: /falldamage <on|off|status>",
                    NamedTextColor.RED));
        }
        return true;
    }

    // ---------- фізика ----------

    /** Кожен тік: тіло ковзає, місця і модель їдуть за ним; сидячий повертає санки за поглядом. */
    public void tick() {
        Iterator<Map.Entry<UUID, Double>> it = lastY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Double> entry = it.next();
            UUID id = entry.getKey();
            Entity e = Bukkit.getEntity(id);
            if (!isSledStand(e)) {
                it.remove();
                cleanupOrphan(id);
                continue;
            }
            ArmorStand body = (ArmorStand) e;
            Location bl = body.getLocation();

            ItemDisplay vis = visuals.get(id);
            if (vis != null && vis.isValid()) {
                vis.teleport(bl);
            }
            Interaction box = hitbox.get(id);
            if (box != null && box.isValid()) {
                box.teleport(bl);
            }
            // санки повертаються за напрямком руху (зсув позиції між тіками), а не за поглядом гравця.
            // Поворот обмежений MAX_TURN_PER_TICK, тож без ривків з боку в бік.
            float current = body.getLocation().getYaw();
            float yaw = current;
            org.bukkit.Location prev = LAST_POS.get(id);
            if (prev != null && prev.getWorld() != null && prev.getWorld().equals(bl.getWorld())) {
                double dx = bl.getX() - prev.getX();
                double dz = bl.getZ() - prev.getZ();
                if (dx * dx + dz * dz > MIN_MOVE_SQ) {
                    float target = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float diff = ((target - current) % 360F + 540F) % 360F - 180F;
                    yaw = current + Math.max(-MAX_TURN_PER_TICK, Math.min(MAX_TURN_PER_TICK, diff));
                }
            }
            LAST_POS.put(id, bl.clone());
            body.setRotation(yaw, 0F);
            if (vis != null && vis.isValid()) {
                vis.setRotation(yaw, 0F);
            }
            for (UUID seatId : seatsOf.getOrDefault(id, List.of())) {
                Entity seat = Bukkit.getEntity(seatId);
                if (!isSledStand(seat)) {
                    continue;
                }
                int idx = seatsOf.get(id).indexOf(seatId);
                double[][] offs = seatsOf.get(id).size() == 2 ? OFFSETS_2 : OFFSETS_4;
                seat.teleport(seatLoc(bl, offs[idx], yaw));
            }

            if (!onSnow(bl)) {
                entry.setValue(bl.getY());
                continue;
            }
            Vector v = body.getVelocity();
            double dy = bl.getY() - entry.getValue();
            entry.setValue(bl.getY());

            double[] st = speed.get(id);
            if (st == null) {
                st = new double[]{v.getX(), v.getZ()};
            }
            double vx = st[0] * FRICTION;
            double vz = st[1] * FRICTION;
            double hs = Math.sqrt(vx * vx + vz * vz);
            if (dy < -0.02 && hs > 0.001) {
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
            double launchVy = Double.NaN;
            if (hs > 0.01) {
                double nx = bl.getX() + vx / hs * LOOKAHEAD;
                double nz = bl.getZ() + vz / hs * LOOKAHEAD;
                World w = bl.getWorld();
                double aheadTop = columnTop(w, nx, nz, bl.getY());
                double step = aheadTop - bl.getY();
                if (step > STEP_UP) {
                    vx = 0;
                    vz = 0;
                } else if (step > 0.05) {
                    double climb = Math.min(step, 0.3);
                    body.teleport(new Location(w, bl.getX(), bl.getY() + climb, bl.getZ(), bl.getYaw(), 0F));
                    double keep = Math.max(0.0, 1.0 - UPHILL_LOSS_PER_BLOCK * climb);
                    vx *= keep;
                    vz *= keep;
                } else if (step < -0.3 && body.isOnGround()) {
                    launchVy = hs * LAUNCH_FACTOR;
                    vx *= EDGE_BOOST;
                    vz *= EDGE_BOOST;
                }
            }
            st[0] = vx;
            st[1] = vz;
            speed.put(id, st);
            body.setVelocity(new Vector(vx, Double.isNaN(launchVy) ? v.getY() : launchVy, vz));
        }
    }

    /** Прибирає залишки, якщо тіло зникло без нашого відома. */
    private void cleanupOrphan(UUID bodyId) {
        speed.remove(bodyId);
        seatCount.remove(bodyId);
        ItemDisplay v = visuals.remove(bodyId);
        if (v != null) {
            v.remove();
        }
        Interaction box = hitbox.remove(bodyId);
        if (box != null) {
            bodyOfHitbox.remove(box.getUniqueId());
            box.remove();
        }
        for (UUID seatId : seatsOf.getOrDefault(bodyId, List.of())) {
            bodyOfSeat.remove(seatId);
            Entity seat = Bukkit.getEntity(seatId);
            if (seat != null) {
                seat.remove();
            }
        }
        seatsOf.remove(bodyId);
    }

    /** Найвища верхня поверхня в колонці біля висоти y (0, якщо її немає). */
    private static double columnTop(World w, double x, double z, double y) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.5); by >= (int) Math.floor(y - 2.5); by--) {
            double t = surfaceTop(w.getBlockAt(bx, by, bz));
            if (t > 0) {
                return t;
            }
        }
        return 0.0;
    }

    /** Висота верхньої поверхні блока. */
    private static double surfaceTop(Block b) {
        Material m = b.getType();
        if (m == Material.SNOW) {
            return b.getY() + ((Snow) b.getBlockData()).getLayers() / 8.0;
        }
        if (isSlidable(m) || m.isSolid()) {
            return b.getY() + 1.0;
        }
        return 0.0;
    }

    private boolean onSnow(Location loc) {
        Block at = loc.getBlock();
        Block below = at.getRelative(BlockFace.DOWN);
        return isSlidable(at.getType()) || isSlidable(below.getType());
    }
}
