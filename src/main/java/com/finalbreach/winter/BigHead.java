package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Пасхалка: /bighead збільшує гравця вдвічі (атрибут SCALE). Повторний виклик повертає розмір назад. */
public final class BigHead implements CommandExecutor {

    private final NamespacedKey key;

    public BigHead(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "bighead");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Ця команда тільки для гравців.");
            return true;
        }
        AttributeInstance scale = p.getAttribute(Attribute.SCALE);
        if (scale == null) {
            p.sendMessage(Component.text("Не вдалося змінити розмір.", NamedTextColor.RED));
            return true;
        }
        AttributeModifier existing = null;
        for (AttributeModifier m : scale.getModifiers()) {
            if (key.equals(m.getKey())) {
                existing = m;
                break;
            }
        }
        if (existing != null) {
            scale.removeModifier(existing);
            p.sendMessage(Component.text("Біг хед мод вимкнено.", NamedTextColor.GRAY));
        } else {
            scale.addModifier(new AttributeModifier(key, 1.0, AttributeModifier.Operation.ADD_SCALAR));
            p.sendMessage(Component.text("Біг хед мод увімкнено!", NamedTextColor.GOLD));
        }
        return true;
    }
}
