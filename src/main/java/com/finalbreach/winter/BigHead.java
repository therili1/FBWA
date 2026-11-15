package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Пасхалка: /bighead робить велику голову з обличчям гравця над його головою.
 * Це окремий предмет-дисплей, прикріплений як пасажир, тож тіло гравця не змінюється.
 * Голова повторює поворот (yaw/pitch) гравця і видна власнику (у F5).
 * Повторний виклик прибирає голову.
 */
public final class BigHead implements CommandExecutor, Listener {

    /** Масштаб голови (1 = звичайна). */
    private static final float SCALE = 2.0F;
    /** Висота над макушкою, у блоках. Підлаштуй, якщо голова сидить низько або високо. */
    private static final float LIFT = 0.5F;

    private final WinterPlugin plugin;
    private final Map<UUID, ItemDisplay> heads = new HashMap<>();
    /** Гравці, які бачать власну голову (/bighead self). За замовчуванням ні. */
    private final Set<UUID> selfVisible = new HashSet<>();

    public BigHead(WinterPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Ця команда тільки для гравців.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("self")) {
            UUID id = p.getUniqueId();
            boolean nowVisible = !selfVisible.contains(id);
            if (nowVisible) {
                selfVisible.add(id);
            } else {
                selfVisible.remove(id);
            }
            ItemDisplay active = heads.get(id);
            if (active != null) {
                if (nowVisible) {
                    p.showEntity(plugin, active);
                } else {
                    p.hideEntity(plugin, active);
                }
            }
            p.sendMessage(Component.text(nowVisible ? "Ти бачиш свою голову." : "Ти не бачиш свою голову.", NamedTextColor.GRAY));
            return true;
        }
        ItemDisplay old = heads.remove(p.getUniqueId());
        if (old != null) {
            old.remove();
            p.sendMessage(Component.text("Біг хед мод вимкнено.", NamedTextColor.GRAY));
            return true;
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(p);
        head.setItemMeta(meta);

        ItemDisplay display = p.getWorld().spawn(p.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(head);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            d.setTransformation(new Transformation(
                    new Vector3f(0, LIFT, 0),
                    new Quaternionf(),
                    new Vector3f(SCALE, SCALE, SCALE),
                    new Quaternionf()));
            d.setInterpolationDuration(1);
            d.setPersistent(false);
        });
        p.addPassenger(display);
        if (!selfVisible.contains(p.getUniqueId())) {
            p.hideEntity(plugin, display);
        }
        heads.put(p.getUniqueId(), display);
        p.sendMessage(Component.text("Біг хед мод увімкнено!", NamedTextColor.GOLD));
        return true;
    }

    /** Кожен тік повторює поворот голови гравця. */
    private void tick() {
        for (Iterator<Map.Entry<UUID, ItemDisplay>> it = heads.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, ItemDisplay> e = it.next();
            ItemDisplay d = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || !d.isValid()) {
                if (d.isValid()) {
                    d.remove();
                }
                it.remove();
                continue;
            }
            Location l = p.getLocation();
            d.setRotation(l.getYaw(), 0F);
            d.setTransformation(new Transformation(
                    new Vector3f(0, LIFT, 0),
                    new Quaternionf().rotateX((float) Math.toRadians(l.getPitch())),
                    new Vector3f(SCALE, SCALE, SCALE),
                    new Quaternionf()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        remove(e.getEntity().getUniqueId());
    }

    private void remove(UUID id) {
        ItemDisplay d = heads.remove(id);
        if (d != null) {
            d.remove();
        }
    }
}
