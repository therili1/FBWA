package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Раз на пів секунди: бере "відчутну" температуру гравця (надворі + укриття + тепло від вогню),
 * рахує, скільки тепла не вистачає, накопичує переохолодження й наносить шкоду.
 */
public final class ColdSystem implements Runnable {

    private static final double DT = 0.5; // секунд між запусками (10 тіків)
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final class Pending {
        double seconds; // скільки ще не записано в предмет
        long seen;
    }

    private static final class State {
        double exposure;
        double damageTimer;
        double felt = Double.NaN; // температура, яку гравець відчуває (плавно наздоганяє цільову)
        double wet;               // скільки секунд ще мокрий
        int tick;
        final Map<String, Pending> pending = new HashMap<>();
    }

    private final WinterPlugin plugin;
    private final Heaters heaters;
    private final HeatField heat;
    private final Map<UUID, State> states = new HashMap<>();

    public ColdSystem(WinterPlugin plugin, Heaters heaters, HeatField heat) {
        this.plugin = plugin;
        this.heaters = heaters;
        this.heat = heat;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                process(p, now);
            } catch (Exception ex) {
                plugin.getLogger().warning("Помилка обробки холоду для " + p.getName() + ": " + ex);
            }
        }
    }

    private void process(Player p, long now) {
        Settings s = plugin.settings();
        World w = p.getWorld();
        State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());

        if (!plugin.isManaged(w)
                || p.getGameMode() == GameMode.CREATIVE
                || p.getGameMode() == GameMode.SPECTATOR
                || p.isDead()
                || p.hasPermission("finalbreach.winter.immune")) {
            deactivate(p, st);
            return;
        }

        // 0. Яку температуру гравець відчуває (змінюється плавно)
        HeatField.Local local = heat.local(p);
        double target = local != null ? local.temp() : plugin.outsideTemp(p.getLocation());
        if (Double.isNaN(st.felt)) {
            st.felt = target;
        } else {
            double tau = target < st.felt ? s.feelCool : s.feelWarm;
            st.felt += (target - st.felt) * (1.0 - Math.exp(-DT / tau));
        }
        double temp = st.felt;

        // Вітер: на відкритому місці під час хуртовини температура відчувається нижчою (без тепла від печей)
        if (local == null || local.heat() < 0.1) {
            World ww = p.getWorld();
            var loc = p.getLocation();
            boolean skyOpen = ww.getHighestBlockYAt(loc) <= loc.getBlockY() + 1;
            boolean stormNow = plugin.climate(ww).at(ww.getFullTime()).precip() != Climate.Precip.CLEAR;
            if (skyOpen && stormNow) {
                temp -= plugin.getConfig().getDouble("wind.chill-degrees", 6.0);
            }
        }

        // 0.5 Мокрість: у воді - повна, потім сохне (біля вогню і в теплі швидше)
        if (s.wetEnabled) {
            if (p.isInWater()) {
                st.wet = s.wetSeconds;
            } else if (st.wet > 0.0) {
                double dry = 1.0 + (local != null ? local.heat() * s.wetHeatDry : 0.0) + (temp >= s.safeAbove ? 2.0 : 0.0);
                st.wet = Math.max(0.0, st.wet - DT * dry);
            }
        } else {
            st.wet = 0.0;
        }

        double cold = s.coldFactor(temp);
        double required = s.requiredWarmth(temp);
        boolean needed = required > 0.001;

        // 1. Скільки тепла дає броня
        double warmth = 0.0;
        int open = 0; // слоти без металу: порожні, шкіряні тощо (там вода не стікає)
        for (EquipmentSlot slot : ARMOR) {
            ItemStack it = p.getInventory().getItem(slot);
            if (it == null || it.getType().isAir() || !isMetal(it.getType())) {
                open++;
            }
            if (it == null || it.getType().isAir()) {
                continue;
            }
            if (Heaters.isLeather(it.getType())) {
                warmth += 1.0;
                continue;
            }
            if (!heaters.has(it)) {
                continue;
            }
            String id = heaters.id(it);
            if (id == null) {
                continue;
            }
            Pending pd = st.pending.computeIfAbsent(id, k -> new Pending());
            pd.seen = now;
            warmth += heaters.factor(it, pd.seconds);

            // грілка "горить", лише поки гравцеві холодно
            if (needed) {
                pd.seconds += DT;
                if (pd.seconds >= s.loreUpdate) {
                    flush(p, slot, it, pd, id, st);
                }
            }
        }
        st.pending.entrySet().removeIf(e -> now - e.getValue().seen > 600_000L);

        // 1.5 Мокрий одяг: у холоді треба більше тепла, але не для металевої броні
        boolean wetNow = st.wet > 0.5;
        if (wetNow && required > 0.001) {
            required += s.wetPenalty * Math.min(1.0, st.wet / s.wetSeconds) * (open / 4.0);
        }

        // 2. Нестача тепла і накопичення переохолодження
        double deficit = Math.max(0.0, required - warmth);
        if (deficit > 0.001) {
            st.exposure += DT;
        } else {
            st.exposure = Math.max(0.0, st.exposure - DT * 2.0);
            st.damageTimer = 0.0;
        }

        // 2.5 Холод забирає сили: їжа витрачається швидше
        if (deficit > 0.001 && s.hungerRate > 0.0) {
            double k = Math.min(1.0, deficit) * (0.3 + 0.7 * cold);
            p.setExhaustion((float) (p.getExhaustion() + s.hungerRate * k * DT));
        }

        // 3. Шкода: чим холодніше і чим менше тепла, тим частіше
        if (st.exposure >= s.grace && deficit > 0.001) {
            double severity = deficit * (0.5 + 1.5 * cold);
            double interval = Math.max(s.minInterval, Math.min(s.maxInterval, s.baseInterval / severity));
            st.damageTimer += DT;
            if (st.damageTimer >= interval) {
                st.damageTimer = 0.0;
                p.damage(s.damage, DamageSource.builder(DamageType.FREEZE).build());
            }
        }

        // 4. Візуал і підказка
        if (s.frost && st.exposure > 0.0) {
            double ratio = Math.min(1.0, st.exposure / (s.grace * 2.0 + 0.001));
            p.setFreezeTicks((int) (ratio * 120)); // нижче 140, щоб не вмикалась ванільна шкода
        }
        // остання стадія: коли паморозь майже закрила екран, гравець ще й сповільнюється
        if (s.slowdown && deficit > 0.001) {
            double stage = Math.min(1.0, st.exposure / (s.grace * 2.0 + 0.001));
            if (stage >= s.slowStage) {
                PotionEffect cur = p.getPotionEffect(PotionEffectType.SLOWNESS);
                if (cur == null || cur.getDuration() < 30) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, true, false, false));
                }
            }
        }
        if (s.actionbar && (st.tick++ % 4) == 0) {
            p.sendActionBar(statusLine(temp, warmth, required, needed, deficit, wetNow));
        }
    }

    /**
     * Рядок над хотбаром: температура з кольором, смужка тепла (■ набрано, □ ні) і статус.
     * Колір температури: синій холод, білий нейтрально, жовтий тепло. Смужка зелена, коли тепла вистачає.
     */
    static Component statusLine(double temp, double warmth, double required, boolean needed,
                                double deficit, boolean wet) {
        int t = (int) Math.round(temp);
        NamedTextColor tc = t <= -10 ? NamedTextColor.AQUA
                : t <= 5 ? NamedTextColor.WHITE : NamedTextColor.YELLOW;
        Component line = Component.text("❄ ", NamedTextColor.DARK_AQUA)
                .append(Component.text(t + "°C", tc));

        if (needed && required > 0.0) {
            int cells = 10;
            int filled = (int) Math.max(0, Math.min(cells, Math.round(warmth / required * cells)));
            boolean ok = deficit <= 0.001;
            NamedTextColor barColor = ok ? NamedTextColor.GREEN : NamedTextColor.RED;
            line = line.append(Component.text("   "))
                    .append(Component.text("■".repeat(filled), barColor))
                    .append(Component.text("□".repeat(cells - filled), NamedTextColor.DARK_GRAY))
                    .append(Component.text(String.format(Locale.US, "  %.1f/%.1f", warmth, required), NamedTextColor.GRAY));
        } else {
            line = line.append(Component.text("   ✔ Комфортно", NamedTextColor.GREEN));
        }

        if (wet) {
            line = line.append(Component.text("   ● Мокрий", NamedTextColor.BLUE));
        }
        return line;
    }

    /** Метал і панцир: вода стікає, броня не мокне. */
    private static boolean isMetal(Material m) {
        String n = m.name();
        return n.startsWith("IRON_") || n.startsWith("GOLDEN_") || n.startsWith("CHAINMAIL_")
                || n.startsWith("DIAMOND_") || n.startsWith("NETHERITE_") || n.startsWith("COPPER_")
                || n.equals("TURTLE_HELMET");
    }

    /** Записує накопичені секунди в сам предмет (залишок + лор). */
    private void flush(Player p, EquipmentSlot slot, ItemStack it, Pending pd, String id, State st) {
        int use = (int) Math.floor(pd.seconds);
        if (use <= 0) {
            return;
        }
        pd.seconds -= use;
        int left = heaters.left(it) - use;
        boolean alive = heaters.setLeft(it, left);
        p.getInventory().setItem(slot, it);
        if (!alive) {
            st.pending.remove(id);
            p.sendMessage(Component.text("Грілка на твоїй броні згасла.", NamedTextColor.RED));
        }
    }

    private void flushEquipped(Player p, State st) {
        for (EquipmentSlot slot : ARMOR) {
            ItemStack it = p.getInventory().getItem(slot);
            if (it == null || it.getType().isAir() || !heaters.has(it)) {
                continue;
            }
            String id = heaters.id(it);
            Pending pd = id == null ? null : st.pending.get(id);
            if (pd != null) {
                flush(p, slot, it, pd, id, st);
            }
        }
    }

    private void deactivate(Player p, State st) {
        if (st.exposure > 0.0 || !st.pending.isEmpty()) {
            flushEquipped(p, st);
        }
        st.exposure = 0.0;
        st.damageTimer = 0.0;
        st.felt = Double.NaN;
        st.wet = 0.0;
    }

    /** Викликається при виході гравця і при вимкненні плагіна. */
    public void forget(Player p) {
        State st = states.remove(p.getUniqueId());
        if (st != null) {
            flushEquipped(p, st);
        }
    }

    public void forgetAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            forget(p);
        }
        states.clear();
    }
}
