package com.finalbreach.winter;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Тепло в світі.
 *
 * Як це працює:
 *  1. Джерела - пічки, плавильні, коптильні, багаття (запалені дають тепло).
 *  2. Печі, що торкаються гранями (і ланцюжком через інші печі), - одна ГРУПА. Потужність групи - сума
 *     запалених печей, а дальність і обсяг приміщень, які вона здатна обігріти, ростуть з їх кількістю.
 *     Тому ряд печей у котельні обігріває весь дім. Багаття завжди окреме.
 *  3. Від групи тепло "розтікається" по повітрю (пошук вшир): стіни, дах і зачинені двері його тримають,
 *     дірки й відкрите небо - випускають. Рівновага = потужність / втрати.
 *  4. Ближче до джерела тепліше. Поруч із відкритим вогнем завжди є трохи "променистого" тепла.
 *  5. Стіни й дах без джерела дають лише невеликий бонус, а глибоко під землею - більший.
 *  6. Група розігрівається і остигає поступово, а не стрибком.
 */
public final class HeatField implements Runnable {

    /** Що відчуває гравець: температура надворі, бонус укриття, тепло від джерел та підсумок. */
    public record Local(double outside, double bonus, double heat, double temp, int sources) {}

    /** Група з'єднаних джерел (або одне джерело). */
    private record Cluster(List<int[]> members, int lit, double power, double radiant, long key) {}

    private static final long REBUILD_NANOS = 4_000_000_000L;
    private static final long FORGET_NANOS = 120_000_000_000L;
    private static final double BASE_LOSS = 1.5;
    private static final double VOLUME_LOSS = 0.03;
    private static final int MAX_CLUSTER = 20;
    private static final int REACH_CAP = 60;
    private static final double FALLOFF_CUTOFF = 0.02;
    /** Скільки "кроків" втрати дає провідний блок (підлога) порівняно з повітрям (1). */
    private static final int CONDUCTOR_STEP = 2;
    private static final int MAX_CELLS_CAP = 3000;
    private static final int CHUNK_RADIUS = 2;

    private static final int[][] DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private static final class Node {
        double power;
        boolean fresh = true;
        long updated;
        long builtAt;
        long seen;
        long cycle;
        int builtLit = -1;
        int lit;
        int ox;
        int oy;
        int oz;
        int reach;
        Map<Long, Float> cells;
        double leak;
    }

    private final WinterPlugin plugin;
    private final Map<UUID, Map<Long, Node>> nodes = new HashMap<>();
    private final Map<UUID, Local> locals = new HashMap<>();
    private final Map<UUID, Map<Long, Long>> scanned = new HashMap<>();
    private long cycle;
    private double lastLeak;

    public HeatField(WinterPlugin plugin) {
        this.plugin = plugin;
    }

    public Local local(Player p) {
        return locals.get(p.getUniqueId());
    }

    public void forget(UUID id) {
        locals.remove(id);
    }

    @Override
    public void run() {
        cycle++;
        long now = System.nanoTime();
        Settings s = plugin.settings();
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (!plugin.isManaged(w)) {
                locals.remove(p.getUniqueId());
                continue;
            }
            try {
                locals.put(p.getUniqueId(), compute(p, w, s, now));
            } catch (Exception ex) {
                plugin.getLogger().warning("Помилка розрахунку тепла для " + p.getName() + ": " + ex);
            }
        }
        cleanup(now);
    }

    // ---------- розрахунок для гравця ----------

    private Local compute(Player p, World w, Settings s, long now) {
        Location loc = p.getLocation();
        double outside = plugin.outsideTemp(loc);
        double bonus = shelter(p, s);

        Block feet = loc.getBlock();
        Block eye = p.getEyeLocation().getBlock();
        long feetKey = pack(feet.getX(), feet.getY(), feet.getZ());
        long eyeKey = pack(eye.getX(), eye.getY(), eye.getZ());

        List<Node> active = new ArrayList<>();
        scan(w, loc, 0, s, now, active);

        double heat = 0.0;
        int count = 0;
        for (Node n : active) {
            Float a = n.cells.get(feetKey);
            Float b = n.cells.get(eyeKey);
            float best = Math.max(a == null ? 0f : a, b == null ? 0f : b);
            if (best > 0f) {
                heat += best * n.power;
                count += Math.max(1, n.lit);
            }
        }

        double base = outside + bonus;
        double temp = base;
        if (heat > 0.0) {
            temp = Math.max(base, Math.min(s.comfort, base + heat));
        }
        return new Local(outside, bonus, heat, temp, count);
    }

    /**
     * Температура для росту рослин у цьому блоці: надворі + тепло від печей (без бонусу укриття:
     * неопалювана теплиця взимку холодна). Працює і там, де гравців поруч немає.
     */
    public double growthTemp(Block b) {
        World w = b.getWorld();
        Settings s = plugin.settings();
        long now = System.nanoTime();
        double outside = plugin.outsideTemp(b.getLocation());

        long ck = ((long) (b.getX() >> 4) << 32) ^ ((b.getZ() >> 4) & 0xFFFFFFFFL);
        Map<Long, Long> seen = scanned.computeIfAbsent(w.getUID(), k -> new HashMap<>());
        Long last = seen.get(ck);
        if (last == null || now - last > REBUILD_NANOS) {
            seen.put(ck, now);
            scan(w, b.getLocation(), 8, s, now, new ArrayList<>());
        }

        long key = pack(b.getX(), b.getY(), b.getZ());
        double heat = 0.0;
        Map<Long, Node> ns = nodes.get(w.getUID());
        if (ns != null) {
            for (Node n : ns.values()) {
                if (n.cells == null || n.power < 0.02) {
                    continue;
                }
                // Без грубої коробки навколо однієї печі: група може бути довгим ланцюжком,
                // а клітинки вже обмежені радіусом і бюджетом при побудові.
                Float v = n.cells.get(key);
                if (v != null) {
                    heat += v * n.power;
                }
            }
        }
        return heat > 0.0 ? Math.max(outside, Math.min(s.comfort, outside + heat)) : outside;
    }

    /** Знаходить групи джерел біля точки, оновлює їх і складає активні (з теплом) у out. */
    private void scan(World w, Location focus, double margin, Settings s, long now, List<Node> out) {
        int cx = focus.getBlockX() >> 4;
        int cz = focus.getBlockZ() >> 4;
        Set<Material> types = s.sources.keySet();
        Set<Long> done = new HashSet<>();

        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                if (!w.isChunkLoaded(cx + dx, cz + dz)) {
                    continue;
                }
                Chunk ch = w.getChunkAt(cx + dx, cz + dz);
                for (BlockState st : ch.getTileEntities(b -> types.contains(b.getType()), false)) {
                    if (done.contains(pack(st.getX(), st.getY(), st.getZ()))) {
                        continue; // уже враховано в складі групи
                    }
                    Cluster cl = cluster(w, st, s);
                    for (int[] m : cl.members()) {
                        done.add(pack(m[0], m[1], m[2]));
                    }
                    if (cl.members().isEmpty()) {
                        continue;
                    }

                    int reach = reachFor(s, cl.lit());
                    double gate = reach + margin;
                    if (nearest2(cl, focus) > gate * gate) {
                        continue;
                    }

                    Node n = node(w, cl.key());
                    advance(n, cl.lit() > 0, now, s);
                    if (n.power < 0.02) {
                        continue;
                    }
                    if (cl.lit() > 0 && (n.cells == null || n.builtLit != cl.lit() || now - n.builtAt > REBUILD_NANOS)) {
                        n.cells = spread(w, cl, s);
                        n.leak = lastLeak;
                        n.builtAt = now;
                        n.builtLit = cl.lit();
                        n.ox = cl.members().get(0)[0];
                        n.oy = cl.members().get(0)[1];
                        n.oz = cl.members().get(0)[2];
                        n.reach = reach;
                    }
                    if (n.cells == null) {
                        continue;
                    }
                    n.lit = cl.lit();
                    out.add(n);
                }
            }
        }
    }

    /** Розігрів і остигання групи. */
    private void advance(Node n, boolean lit, long now, Settings s) {
        n.seen = now;
        if (!n.fresh && now - n.updated < 500_000_000L) {
            return;
        }
        if (n.fresh) {
            n.fresh = false;
            n.power = lit ? 1.0 : 0.0;
        } else {
            double dt = Math.max(0.1, (now - n.updated) / 1e9);
            // Закрите приміщення (малий leak) тримає тепло довше; відкрите остигає майже за base cooldown.
            if (lit) {
                n.power += (1.0 - n.power) * (1.0 - Math.exp(-dt / s.warmup));
            } else {
                // Лінійне остигання до нуля: закрите (leak≈0) — sealedCooldown, відкрите — cooldown.
                double t = s.cooldown + (s.sealedCooldown - s.cooldown) / (1.0 + n.leak);
                n.power = Math.max(0.0, n.power - dt / t);
            }
        }
        n.updated = now;
    }

    private Node node(World w, long key) {
        return nodes.computeIfAbsent(w.getUID(), k -> new HashMap<>()).computeIfAbsent(key, k -> new Node());
    }

    private void cleanup(long now) {
        for (Iterator<Map<Long, Node>> it = nodes.values().iterator(); it.hasNext(); ) {
            Map<Long, Node> m = it.next();
            m.values().removeIf(n -> now - n.seen > FORGET_NANOS);
            if (m.isEmpty()) {
                it.remove();
            }
        }
        locals.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        for (Iterator<Map<Long, Long>> it = scanned.values().iterator(); it.hasNext(); ) {
            Map<Long, Long> m = it.next();
            m.values().removeIf(t -> now - t > 60_000_000_000L);
            if (m.isEmpty()) {
                it.remove();
            }
        }
    }

    // ---------- групи печей ----------

    private static boolean solo(Material m) {
        return m.name().contains("CAMPFIRE");
    }

    private static int reachFor(Settings s, int lit) {
        return (int) Math.min(REACH_CAP, Math.ceil(s.heatDecay * 4.0));
    }

    /** Тепло слабшає експоненційно з відстанню по повітрю (перші 2 блоки без втрат). */
    static double falloff(int d, double decay) {
        return d <= 2 ? 1.0 : Math.exp(-(d - 2) / decay);
    }

    private static double nearest2(Cluster cl, Location loc) {
        double best = Double.MAX_VALUE;
        for (int[] m : cl.members()) {
            double dx = m[0] - loc.getX();
            double dy = m[1] - loc.getY();
            double dz = m[2] - loc.getZ();
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    /**
     * Збирає групу: усі печі, з'єднані гранями з цією (ланцюжком). Холодна піч у ланцюжку з'єднує,
     * але потужності не додає: рахуються лише запалені.
     */
    private Cluster cluster(World w, BlockState first, Settings s) {
        List<int[]> members = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        boolean solo = solo(first.getType());
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        int[] start = {first.getX(), first.getY(), first.getZ()};
        queue.add(start);
        seen.add(pack(start[0], start[1], start[2]));

        int lit = 0;
        double power = 0.0;
        double radiant = 0.0;
        long key = Long.MAX_VALUE;

        while (!queue.isEmpty() && members.size() < MAX_CLUSTER) {
            int[] c = queue.poll();
            Block b = w.getBlockAt(c[0], c[1], c[2]);
            Settings.Source src = s.sources.get(b.getType());
            if (src == null) {
                continue;
            }
            members.add(c);
            key = Math.min(key, pack(c[0], c[1], c[2]));
            if (b.getBlockData() instanceof Lightable l && l.isLit()) {
                lit++;
                power += src.power();
                radiant = Math.max(radiant, src.radiant());
            }
            if (solo) {
                continue;
            }
            for (int[] d : DIRS) {
                int nx = c[0] + d[0];
                int ny = c[1] + d[1];
                int nz = c[2] + d[2];
                if (ny < minY || ny >= maxY) {
                    continue;
                }
                if (!seen.add(pack(nx, ny, nz))) {
                    continue;
                }
                if (!w.isChunkLoaded(nx >> 4, nz >> 4)) {
                    continue;
                }
                Material m = w.getBlockAt(nx, ny, nz).getType();
                if (s.sources.containsKey(m) && !solo(m)) {
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }
        return new Cluster(members, lit, power, radiant, key);
    }

    // ---------- розтікання тепла ----------

    /**
     * Пошук вшир від усіх печей групи по прохідних клітинках. Повертає: клітинка -> градуси при повній потужності.
     * Втрати рахуємо по "гранях": кожна грань стіни, що межує з приміщенням, має свою втратність,
     * кожна грань відкритої межі (небо, дірка, занадто далеко) - повну.
     */
    private Map<Long, Float> spread(World w, Cluster cl, Settings s) {
        int range = reachFor(s, cl.lit());
        int maxCells = Math.min(MAX_CELLS_CAP, s.heatCells * Math.max(1, cl.lit()));

        Map<Long, Integer> dist = new HashMap<>();
        Map<Long, Boolean> loaded = new HashMap<>();
        Set<Long> origins = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int[] m : cl.members()) {
            origins.add(pack(m[0], m[1], m[2]));
            queue.add(new int[]{m[0], m[1], m[2], 0});
        }

        double leak = 0.0;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            boolean origin = c[3] == 0;
            for (int[] d : DIRS) {
                int nx = c[0] + d[0];
                int ny = c[1] + d[1];
                int nz = c[2] + d[2];
                if (ny < minY || ny >= maxY) {
                    if (!origin) {
                        leak += 1.0;
                    }
                    continue;
                }
                long key = pack(nx, ny, nz);
                if (origins.contains(key) || dist.containsKey(key)) {
                    continue;
                }
                if (!isLoaded(w, loaded, nx >> 4, nz >> 4)) {
                    if (!origin) {
                        leak += 0.5;
                    }
                    continue;
                }
                Block b = w.getBlockAt(nx, ny, nz);
                boolean pass = b.isPassable() && !b.isLiquid();
                boolean conductor = !pass && s.conductors.contains(b.getType());
                boolean pipe = !pass && s.pipes.contains(b.getType());
                if (pass || conductor || pipe) {
                    int step = pass ? 1 : (pipe ? s.pipeStep : CONDUCTOR_STEP);
                    if (falloff(c[3] + step, s.heatDecay) < FALLOFF_CUTOFF || dist.size() >= maxCells) {
                        leak += 1.0; // межа пошуку = "відкритий простір"
                        continue;
                    }
                    dist.put(key, c[3] + step);
                    if (pass && isOpening(b.getType())) {
                        leak += OPEN_LEAK; // відчинені двері/люк/хвіртка: тепло тікає
                    }
                    queue.add(new int[]{nx, ny, nz, c[3] + step});
                } else if (!origin) {
                    leak += leakOf(b.getType());
                }
            }
        }

        lastLeak = leak;
        double loss = BASE_LOSS + leak + dist.size() * VOLUME_LOSS;
        double room = cl.power() / loss;
        Map<Long, Float> out = new HashMap<>(dist.size() * 2);
        for (Map.Entry<Long, Integer> e : dist.entrySet()) {
            int d = e.getValue();
            double falloff = falloff(d, s.heatDecay);
            double v = room * falloff + cl.radiant() * Math.max(0.0, 1.0 - d / 4.0);
            out.put(e.getKey(), (float) v);
        }
        return out;
    }

    private static boolean isLoaded(World w, Map<Long, Boolean> cache, int cx, int cz) {
        long k = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Boolean v = cache.get(k);
        if (v == null) {
            v = w.isChunkLoaded(cx, cz);
            cache.put(k, v);
        }
        return v;
    }

    /** Наскільки стіна з цього матеріалу випускає тепло (менше = краще тримає). */
    /** Втрата тепла через відчинений отвір (двері, люк, хвіртка). */
    private static final double OPEN_LEAK = 0.8;

    /** Прохідний блок-отвір: відчинені двері, люк або хвіртка. */
    private static boolean isOpening(Material m) {
        String n = m.name();
        return n.contains("DOOR") || n.contains("TRAPDOOR") || n.contains("GATE");
    }

    private static double leakOf(Material m) {
        String n = m.name();
        if (n.contains("WOOL") || n.contains("CARPET")) {
            return 0.04;
        }
        if (n.contains("IRON_BARS")) {
            return 0.6;
        }
        if (n.contains("PANE") || n.contains("GLASS")) {
            return 0.25;
        }
        if (n.contains("GATE")) {
            return 0.12;
        }
        if (n.contains("FENCE") || n.endsWith("_WALL")) {
            return 0.5;
        }
        if (n.contains("DOOR")) {
            return 0.14;
        }
        if (n.contains("LEAVES")) {
            return 0.3;
        }
        if (n.endsWith("_PLANKS") || n.endsWith("_LOG") || n.endsWith("_WOOD")) {
            return 0.05;
        }
        return 0.09;
    }

    // ---------- укриття ----------

    /** Невеликий бонус від стін і даху; під землею - більший. */
    private double shelter(Player p, Settings s) {
        Block eye = p.getEyeLocation().getBlock();
        double roof = blocked(eye, BlockFace.UP, 12) ? 1.0 : 0.0;
        int walls = 0;
        for (BlockFace f : HORIZONTAL) {
            if (blocked(eye, f, 6)) {
                walls++;
            }
        }
        double sheltered = s.shelterBonus * (0.5 * roof + 0.5 * walls / 4.0);

        double cave = 0.0;
        int top = eye.getWorld().getHighestBlockYAt(eye.getX(), eye.getZ(), HeightMap.MOTION_BLOCKING);
        double depth = top - eye.getY();
        if (depth >= 6) {
            cave = s.caveBonus * Math.min(1.0, (depth - 5.0) / 10.0);
        }
        return Math.max(sheltered, cave);
    }

    private static boolean blocked(Block from, BlockFace face, int max) {
        for (int i = 1; i <= max; i++) {
            Block b = from.getRelative(face, i);
            if (!b.isPassable() && !b.isLiquid()) {
                return true;
            }
        }
        return false;
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) ((y + 2048) & 0xFFF);
    }
}
