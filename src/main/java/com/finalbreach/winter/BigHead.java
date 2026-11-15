package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.Map;
import java.util.UUID;

/**
 * Пасхалка: /bighead робить велику голову з обличчям гравця над його головою.
 * Це окремий предмет-дисплей, прикріплений як пасажир, тож тіло гравця не змінюється.
 * Повторний виклик прибирає голову.
 */
public final class BigHead implements CommandExecutor, Listener {

    /** Масштаб голови (1 = звичайна). */
    private static final float SCALE = 2.0F;
    /** Висота над макушкою, у блоках. Підлаштуй, якщо голова сидить низько або високо. */
    private static final float LIFT = 0.5F;

    private final Map<UUID, ItemDisplay> heads = new HashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Ця команда тільки для гравців.");
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
            d.setPersistent(false);
        });
        p.addPassenger(display);
        heads.put(p.getUniqueId(), display);
        p.sendMessage(Component.text("Біг хед мод увімкнено!", NamedTextColor.GOLD));
        return true;
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
