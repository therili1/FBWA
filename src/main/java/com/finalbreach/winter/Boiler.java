package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Котельня: група печей з одним спільним запасом палива.
 * /boiler set | unset | info. Паливо (відро лави, вугілля, блейз-стрижень) кладеться ПКМ по будь-якій печі групи.
 */
public final class Boiler implements Listener, CommandExecutor {

    private static final class Group {
        final String id;
        final List<Location> members = new ArrayList<>();
        double fuel;

        Group(String id) {
            this.id = id;
        }
    }

    private static final Map<Material, Integer> DEFAULT_FUEL = Map.of(
            Material.LAVA_BUCKET, 300,
            Material.COAL, 80,
            Material.CHARCOAL, 80,
            Material.BLAZE_ROD, 120);

    private final WinterPlugin plugin;
    private final NamespacedKey kGroup;
    private final Map<String, Group> groups = new HashMap<>();
    private final File file;
    private int saveCounter;

    public Boiler(WinterPlugin plugin) {
        this.plugin = plugin;
        this.kGroup = new NamespacedKey(plugin, "boiler_group");
        this.file = new File(plugin.getDataFolder(), "boilers.yml");
        load();
    }

    private static boolean isFurnace(Material m) {
        return m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER;
    }

    private String groupIdOf(Block b) {
        if (!isFurnace(b.getType())) {
            return null;
        }
        BlockState s = b.getState();
        if (!(s instanceof TileState ts)) {
            return null;
        }
        return ts.getPersistentDataContainer().get(kGroup, PersistentDataType.STRING);
    }

    private void mark(Block b, String id) {
        BlockState s = b.getState();
        if (s instanceof TileState ts) {
            if (id == null) {
                ts.getPersistentDataContainer().remove(kGroup);
            } else {
                ts.getPersistentDataContainer().set(kGroup, PersistentDataType.STRING, id);
            }
            ts.update(true, false);
        }
    }

    private int maxMembers() {
        return Math.max(1, plugin.getConfig().getInt("boiler.max-members", 20));
    }

    private double maxFuel() {
        return plugin.getConfig().getDouble("boiler.max-fuel-seconds", 3600);
    }

    private int fuelSeconds(Material m) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("boiler.fuel");
        if (sec != null && sec.contains(m.name())) {
            return sec.getInt(m.name());
        }
        return DEFAULT_FUEL.getOrDefault(m, 0);
    }

    /** Усі печі, з'єднані гранями з початковою (до maxMembers). */
    private List<Block> cluster(Block start, int max) {
        List<Block> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<Block> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start.getX() + "," + start.getY() + "," + start.getZ());
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty() && out.size() < max) {
            Block b = q.poll();
            out.add(b);
            for (int[] d : dirs) {
                Block n = b.getRelative(d[0], d[1], d[2]);
                if (isFurnace(n.getType()) && seen.add(n.getX() + "," + n.getY() + "," + n.getZ())) {
                    q.add(n);
                }
            }
        }
        return out;
    }

    // ---------- команди ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Ця команда тільки для гравців.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "info";
        Block target = p.getTargetBlockExact(6);
        switch (sub) {
            case "set" -> {
                if (target == null || !isFurnace(target.getType())) {
                    p.sendMessage(Component.text("Подивись на піч, яку треба позначити.", NamedTextColor.RED));
                    return true;
                }
                String id = UUID.randomUUID().toString();
                Group g = new Group(id);
                List<Block> cl = cluster(target, maxMembers());
                for (Block b : cl) {
                    mark(b, id);
                    g.members.add(b.getLocation());
                }
                groups.put(id, g);
                save();
                p.sendMessage(Component.text("Котельню позначено: " + cl.size() + " печей. Паливо кидай ПКМ по будь-якій печі.", NamedTextColor.GREEN));
            }
            case "unset" -> {
                String id = target == null ? null : groupIdOf(target);
                if (id == null) {
                    p.sendMessage(Component.text("Подивись на піч котельні.", NamedTextColor.RED));
                    return true;
                }
                Group g = groups.remove(id);
                if (g != null) {
                    for (Location l : g.members) {
                        mark(l.getBlock(), null);
                    }
                }
                save();
                p.sendMessage(Component.text("Котельню знято.", NamedTextColor.YELLOW));
            }
            default -> {
                String id = target == null ? null : groupIdOf(target);
                Group g = id == null ? null : groups.get(id);
                if (g == null) {
                    p.sendMessage(Component.text("Це не котельня. Використай /boiler set на піч.", NamedTextColor.RED));
                    return true;
                }
                p.sendMessage(Component.text("Котельня: печей " + g.members.size() + ", палива на " + (int) g.fuel + " с", NamedTextColor.GOLD));
            }
        }
        return true;
    }

    // ---------- паливо ----------

    @EventHandler
    public void onInsert(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null) {
            return;
        }
        String id = groupIdOf(b);
        Group g = id == null ? null : groups.get(id);
        if (g == null) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        int secs = fuelSeconds(held.getType());
        if (secs <= 0) {
            return;
        }
        e.setCancelled(true);
        g.fuel = Math.min(maxFuel(), g.fuel + secs);
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            p.getInventory().setItemInMainHand(held);
        } else {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
        if (held.getType() == Material.LAVA_BUCKET) {
            p.getInventory().addItem(new ItemStack(Material.BUCKET)).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        p.sendActionBar(Component.text("Котельня: " + (int) g.fuel + " с палива", NamedTextColor.GOLD));
        save();
    }

    // ---------- тік: спільна витрата і запалювання печей ----------

    /** Викликається кожні 2 секунди. */
    public void tick() {
        double fixed = plugin.getConfig().getDouble("boiler.fixed-rate", 0.0);
        for (Group g : groups.values()) {
            List<Block> all = new ArrayList<>();
            for (Location l : g.members) {
                Block b = l.getBlock();
                if (isFurnace(b.getType())) {
                    all.add(b);
                }
            }
            if (all.isEmpty() || g.fuel <= 0.0) {
                continue;
            }
            // Фіксована витрата (за замовчуванням — по одній одиниці на кожну запалену піч)
            double rate = fixed > 0.0 ? fixed : all.size();
            g.fuel = Math.max(0.0, g.fuel - rate * 2.0);
            for (Block b : all) {
                lightFurnace(b);
            }
            if (all.size() >= 20) {
                awardNear(g, "twenty_furnaces", "twenty");
            } else if (all.size() >= 5) {
                awardNear(g, "five_furnaces", "done");
            }
        }
        if (++saveCounter % 15 == 0) {
            save();
        }
    }

    private void lightFurnace(Block b) {
        BlockState s = b.getState();
        if (s instanceof org.bukkit.block.Furnace f) {
            f.setBurnTime((short) 100);
            f.update(true, false);
        }
        if (b.getBlockData() instanceof Lightable l && !l.isLit()) {
            l.setLit(true);
            b.setBlockData(l, false);
        }
    }

    private void awardNear(Group g, String advancement, String criterion) {
        if (g.members.isEmpty()) {
            return;
        }
        Location c = g.members.get(0);
        World w = c.getWorld();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(w) && p.getLocation().distanceSquared(c) <= 32 * 32) {
                Achievements.award(p, advancement, criterion);
            }
        }
    }

    // ---------- збереження ----------

    public void shutdown() {
        save();
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Group g : groups.values()) {
            String path = "groups." + g.id;
            y.set(path + ".fuel", g.fuel);
            List<String> ms = new ArrayList<>();
            for (Location l : g.members) {
                ms.add(l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
            }
            y.set(path + ".members", ms);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не вдалося зберегти boilers.yml: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("groups");
        if (sec == null) {
            return;
        }
        for (String id : sec.getKeys(false)) {
            Group g = new Group(id);
            g.fuel = sec.getDouble(id + ".fuel");
            for (String s : sec.getStringList(id + ".members")) {
                String[] a = s.split(",");
                World w = Bukkit.getWorld(a[0]);
                if (w != null && a.length == 4) {
                    g.members.add(new Location(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                }
            }
            groups.put(id, g);
        }
    }
}
