package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Усе, що стосується грілки на броні: NBT (PDC), рядок у лорі, оновлення залишку. */
public final class Heaters {

    private final NamespacedKey keyId;
    private final NamespacedKey keyLeft;
    private final NamespacedKey keyTotal;

    public Heaters(Plugin plugin) {
        keyId = new NamespacedKey(plugin, "heater_id");
        keyLeft = new NamespacedKey(plugin, "heater_left");
        keyTotal = new NamespacedKey(plugin, "heater_total");
    }

    public static boolean isLeather(Material m) {
        return switch (m) {
            case LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS -> true;
            default -> false;
        };
    }

    /** Уся броня, крім шкіряної, її можна "підігріти". */
    public static List<Material> heatableMaterials() {
        List<Material> out = new ArrayList<>();
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.startsWith("LEGACY_")) {
                continue;
            }
            if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                    || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")) {
                out.add(m);
            }
        }
        return out;
    }

    public boolean has(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(keyLeft, PersistentDataType.INTEGER);
    }

    public int left(ItemStack item) {
        Integer v = item.getItemMeta().getPersistentDataContainer().get(keyLeft, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    public int total(ItemStack item) {
        Integer v = item.getItemMeta().getPersistentDataContainer().get(keyTotal, PersistentDataType.INTEGER);
        return v == null || v <= 0 ? 600 : v;
    }

    public String id(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    /** Частка тепла: повна, доки лишається більше 20% часу; далі плавно до 0 в кінці. */
    public double factor(ItemStack item, double pendingSeconds) {
        double left = left(item) - pendingSeconds;
        double ratio = left / total(item);
        return Math.max(0.0, Math.min(1.0, ratio / FADE_START));
    }

    /** Частка часу, після якої грілка починає слабшати. */
    private static final double FADE_START = 0.2;

    /** Повертає копію предмета з новою (повною) грілкою. Якщо грілка вже була, оновлює її. */
    public ItemStack apply(ItemStack source, int totalSeconds) {
        return apply(source, totalSeconds, false);
    }

    /** magma = true: магматична грілка (окрема назва, позначка в PDC). */
    public ItemStack apply(ItemStack source, int totalSeconds, boolean magma) {
        ItemStack out = source.clone();
        out.setAmount(1);
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        clearTags(meta);
        if (magma) {
            meta.displayName(Component.text("Магматична грілка", NamedTextColor.GOLD));
        }

        PersistentDataContainer c = meta.getPersistentDataContainer();
        c.set(keyId, PersistentDataType.STRING, UUID.randomUUID().toString());
        c.set(keyLeft, PersistentDataType.INTEGER, totalSeconds);
        c.set(keyTotal, PersistentDataType.INTEGER, totalSeconds);

        List<Component> lore = lore(meta);
        lore.add(line(totalSeconds, totalSeconds));
        meta.lore(lore);
        out.setItemMeta(meta);
        return out;
    }

    /**
     * Записує залишок прямо в предмет. Повертає false, якщо грілка вичерпалась
     * (тоді з предмета прибрано і теги, і рядок у лорі).
     */
    public boolean setLeft(ItemStack item, int left) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (left <= 0) {
            clearTags(meta);
            item.setItemMeta(meta);
            return false;
        }
        PersistentDataContainer c = meta.getPersistentDataContainer();
        int total = c.getOrDefault(keyTotal, PersistentDataType.INTEGER, left);
        c.set(keyLeft, PersistentDataType.INTEGER, left);

        List<Component> lore = lore(meta);
        if (lore.isEmpty()) {
            lore.add(line(left, total));
        } else {
            lore.set(lore.size() - 1, line(left, total));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return true;
    }

    private void clearTags(ItemMeta meta) {
        PersistentDataContainer c = meta.getPersistentDataContainer();
        if (!c.has(keyLeft, PersistentDataType.INTEGER)) {
            return;
        }
        List<Component> lore = lore(meta);
        if (!lore.isEmpty()) {
            lore.remove(lore.size() - 1); // рядок грілки завжди останній
        }
        meta.lore(lore.isEmpty() ? null : lore);
        c.remove(keyId);
        c.remove(keyLeft);
        c.remove(keyTotal);
    }

    private static List<Component> lore(ItemMeta meta) {
        List<Component> l = meta.lore();
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    private static Component line(int left, int total) {
        double pct = total > 0 ? (double) left / total : 0.0;
        TextColor color = pct > 0.5 ? NamedTextColor.GOLD
                : pct > 0.2 ? NamedTextColor.RED : NamedTextColor.DARK_RED;
        String text = String.format("Грілка: %d:%02d (%d%%)", left / 60, left % 60, Math.round(pct * 100));
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
