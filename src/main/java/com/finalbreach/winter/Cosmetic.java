package com.finalbreach.winter;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Косметика: комплекти броні і інструментів. Адмін видає командами:
 *   /cosmetic give <гравець> <предмет>
 *   /cosmetic giveset <гравець> <набір>
 *   /cosmetic list
 * CMD предметів: 3000 + номер набору * 100 + номер слота (0-3 броня, 4-8 інструменти).
 */
public final class Cosmetic implements CommandExecutor, TabCompleter {

    /** Слоти в наборі (індекс = CMD-зсув). */
    public enum Part { HELMET, CHESTPLATE, LEGGINGS, BOOTS, SWORD, PICKAXE, AXE, SHOVEL, HOE }

    public record Piece(String id, String name, Material material, Part part, int cmd) {
        public boolean armor() {
            return part.ordinal() <= Part.BOOTS.ordinal();
        }
    }

    public record SetDef(String id, String name, List<Piece> pieces) {}

    private static final List<SetDef> SETS = new ArrayList<>();

    static {
        // Броня + інструменти
        addSet(0, "krygova_guild", "Кригова гільдія", true, false, "Крижан", Material.DIAMOND);
        addSet(1, "polar_guard", "Полярна варта", true, false, "Полярн", Material.DIAMOND);
        addSet(2, "elven_forest", "Ельфійський ліс", true, false, "Ельфійськ", Material.DIAMOND);
        // Лише броня
        addSet(3, "leather_hunter", "Шкіряний мисливець", false, true, "Мисливськ", Material.LEATHER);
        addSet(4, "crystal_knight", "Кришталевий рицар", false, true, "Кришталев", Material.DIAMOND);
        addSet(5, "newyear_gnome", "Новорічний гном", false, true, "Новорічн", Material.LEATHER);
        // Лише інструменти
        addSet(6, "wreath", "Вінковий", true, false, "Вінков", Material.DIAMOND);
        addSet(7, "candy_fair", "Солодкий ярмарок", true, false, "Солодк", Material.DIAMOND);
        addSet(8, "garland", "Гірлянди", true, false, "Гірляндн", Material.DIAMOND);
    }

    private static void addSet(int index, String id, String name, boolean tools, boolean armorOnly,
                               String adj, Material base) {
        List<Piece> pieces = new ArrayList<>();
        int cmdBase = 3000 + index * 100;
        if (!tools || armorOnly) {
            // броня: шолом, нагрудник, штани, черевики (для лише-броньових наборів — теж)
            String[] names = {"шолом", "нагрудник", "штани", "черевики"};
            Material[] mats = armorMaterials(base);
            Part[] parts = {Part.HELMET, Part.CHESTPLATE, Part.LEGGINGS, Part.BOOTS};
            for (int i = 0; i < 4; i++) {
                pieces.add(new Piece(id + "_" + parts[i].name().toLowerCase(Locale.ROOT),
                        capitalize(adj + "ий " + names[i]).replace("Ий ", "Ий "), mats[i], parts[i], cmdBase + i));
            }
        }
        if (tools) {
            String[] names = {"меч", "кирка", "сокира", "лопата", "мотика"};
            Material[] mats = {Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE,
                    Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE};
            Part[] parts = {Part.SWORD, Part.PICKAXE, Part.AXE, Part.SHOVEL, Part.HOE};
            for (int i = 0; i < 5; i++) {
                pieces.add(new Piece(id + "_" + parts[i].name().toLowerCase(Locale.ROOT),
                        capitalize(adj + "а " + names[i]), mats[i], parts[i], cmdBase + 4 + i));
            }
        }
        SETS.add(new SetDef(id, name, pieces));
    }

    private static Material[] armorMaterials(Material base) {
        if (base == Material.LEATHER) {
            return new Material[]{Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE,
                    Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS};
        }
        return new Material[]{Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS};
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private final WinterPlugin plugin;
    private final NamespacedKey idKey;

    public Cosmetic(WinterPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "cosmetic_id");
    }

    public static List<SetDef> sets() {
        return SETS;
    }

    private static Piece findPiece(String id) {
        for (SetDef s : SETS) {
            for (Piece p : s.pieces()) {
                if (p.id().equals(id)) {
                    return p;
                }
            }
        }
        return null;
    }

    private static SetDef findSet(String id) {
        for (SetDef s : SETS) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** Створює предмет косметики. Броня отримує компонент equippable з моделлю набору. */
    public ItemStack build(SetDef set, Piece piece) {
        ItemStack it = new ItemStack(piece.material());
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(piece.name(), NamedTextColor.AQUA));
        meta.setCustomModelData(piece.cmd());
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, piece.id());
        it.setItemMeta(meta);
        if (piece.armor()) {
            EquipmentSlot slot = switch (piece.part()) {
                case HELMET -> EquipmentSlot.HEAD;
                case CHESTPLATE -> EquipmentSlot.CHEST;
                case LEGGINGS -> EquipmentSlot.LEGS;
                default -> EquipmentSlot.FEET;
            };
            it.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(slot)
                    .assetId(Key.key("finalbreach", set.id()))
                    .build());
        }
        return it;
    }

    private void giveItems(Player target, List<ItemStack> items) {
        for (ItemStack it : items) {
            target.getInventory().addItem(it).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("finalbreach.cosmetic.admin")) {
            sender.sendMessage(Component.text("Немає прав на цю команду.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("Використання: /cosmetic <give|giveset|list>", NamedTextColor.RED));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                for (SetDef s : SETS) {
                    sender.sendMessage(Component.text(s.id() + " — " + s.name() + ": "
                            + s.pieces().stream().map(Piece::id).reduce((a, b) -> a + ", " + b).orElse("-"),
                            NamedTextColor.GRAY));
                }
            }
            case "give" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Використання: /cosmetic give <гравець> <предмет>", NamedTextColor.RED));
                    return true;
                }
                Player target = plugin.getServer().getPlayerExact(args[1]);
                Piece piece = findPiece(args[2]);
                SetDef set = piece == null ? null : setOf(piece);
                if (target == null || piece == null) {
                    sender.sendMessage(Component.text("Гравця або предмета не знайдено.", NamedTextColor.RED));
                    return true;
                }
                giveItems(target, List.of(build(set, piece)));
                sender.sendMessage(Component.text("Видано " + piece.name() + " гравцю " + target.getName(), NamedTextColor.GREEN));
            }
            case "giveset" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Використання: /cosmetic giveset <гравець> <набір>", NamedTextColor.RED));
                    return true;
                }
                Player target = plugin.getServer().getPlayerExact(args[1]);
                SetDef set = findSet(args[2]);
                if (target == null || set == null) {
                    sender.sendMessage(Component.text("Гравця або набору не знайдено.", NamedTextColor.RED));
                    return true;
                }
                List<ItemStack> items = new ArrayList<>();
                for (Piece p : set.pieces()) {
                    items.add(build(set, p));
                }
                giveItems(target, items);
                sender.sendMessage(Component.text("Видано набір «" + set.name() + "» гравцю " + target.getName(), NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Використання: /cosmetic <give|giveset|list>", NamedTextColor.RED));
        }
        return true;
    }

    private static SetDef setOf(Piece piece) {
        for (SetDef s : SETS) {
            if (s.pieces().contains(piece)) {
                return s;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("give", "giveset", "list"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("giveset"))) {
            plugin.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            SETS.forEach(s -> s.pieces().forEach(p -> out.add(p.id())));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("giveset")) {
            SETS.forEach(s -> out.add(s.id()));
        }
        return out;
    }
}
