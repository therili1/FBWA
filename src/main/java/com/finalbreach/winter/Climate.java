package com.finalbreach.winter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Детермінований "сценарій" погоди: залежить лише від сіду світу, часу та адмінських змін.
 * Температура безперервна в часі, без стрибків:
 * повільний "фронт" (дрейф між днями) + добовий хід + дрібний шум + плавний вплив опадів.
 * Усе рахується на "базовій шкалі" (temperature.min .. temperature.max), а потім
 * лінійно переноситься на шкалу біому (у теплих біомах мінімум вищий).
 */
public final class Climate {

    public static final int DAY = 24000;

    private static final int RAMP_IN = 1200;      // за скільки тіків набирає силу адмінська зміна
    private static final int HOLD = 12000;        // скільки тримається повністю, потім плавно згасає
    private static final double FRONT_LO = 0.18;  // межі "фронту" у частках діапазону
    private static final double FRONT_HI = 0.93;

    public enum Precip {
        CLEAR("ясно", "○"), SNOW("сніг", "❄"), BLIZZARD("хуртовина", "✷");

        public final String label;
        public final String symbol;

        Precip(String label, String symbol) {
            this.label = label;
            this.symbol = symbol;
        }
    }

    public record Slot(String name, int from) {}

    public record Entry(long day, int index, String name, int fromTick, int toTick, Precip precip) {}

    /** Адмінська зміна: зсув температури (з плавним входом/виходом) і, за бажанням, примусові опади. */
    public record Change(long at, double offset, Precip precip, long until) {}

    private final long seed;
    private final double min;
    private final double max;
    private final int smoothDays;
    private final double swing;
    private final List<Slot> slots;
    private final double snowChance;
    private final double blizzardChance;
    private final double snowMod;
    private final double blizzardMod;
    private final long recoveryTicks;
    private final List<Change> changes = new ArrayList<>();

    public Climate(long seed, Settings s) {
        this.seed = seed;
        this.min = s.tMin;
        this.max = s.tMax;
        this.smoothDays = Math.max(1, s.smoothDays);
        this.swing = s.swing;
        this.slots = List.copyOf(s.slots);
        this.snowChance = s.snowChance;
        this.blizzardChance = s.blizzardChance;
        this.snowMod = s.snowMod;
        this.blizzardMod = s.blizzardMod;
        this.recoveryTicks = Math.max(1L, Math.round(s.recoveryDays * DAY));
    }

    // ---------- відрізки доби ----------

    public int slotCount() {
        return slots.size();
    }

    public int slotIndex(int timeOfDay) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            if (timeOfDay >= slots.get(i).from()) {
                return i;
            }
        }
        return 0;
    }

    private int slotEnd(int idx) {
        return idx + 1 < slots.size() ? slots.get(idx + 1).from() : DAY;
    }

    private long slotStartAbs(long day, int idx) {
        return day * DAY + slots.get(idx).from();
    }

    private long slotEndAbs(long day, int idx) {
        return day * DAY + slotEnd(idx);
    }

    public Entry at(long fullTime) {
        long day = Math.floorDiv(fullTime, (long) DAY);
        int tod = (int) Math.floorMod(fullTime, (long) DAY);
        return entry(day, slotIndex(tod));
    }

    public Entry entry(long day, int index) {
        Slot slot = slots.get(index);
        return new Entry(day, index, slot.name(), slot.from(), slotEnd(index), precip(day, index));
    }

    // ---------- температура ----------

    /** Температура на базовій шкалі. */
    public double base(long t) {
        return clamp(natural(t) + offsetAt(t), min, max);
    }

    /** Температура для конкретного біому. */
    public double temp(long t, Settings.Profile p) {
        return map(base(t), p);
    }

    /** Лінійне перенесення з базової шкали на шкалу біому. */
    public double map(double base, Settings.Profile p) {
        double f = (base - min) / (max - min);
        return p.min() + f * (p.max() - p.min());
    }

    /** [мінімум, максимум] температури на проміжку (для прогнозу). */
    public double[] range(long from, long to, Settings.Profile p, int samples) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i <= samples; i++) {
            long t = from + (to - from) * i / samples;
            double v = temp(t, p);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new double[]{lo, hi};
    }

    private double natural(long t) {
        int tod = (int) Math.floorMod(t, (long) DAY);
        double diurnal = swing * Math.cos(2.0 * Math.PI * (tod - 9000) / DAY);
        double wobble = 1.6 * noise(10, t / 3000.0) + 1.0 * noise(11, t / 900.0);
        return front(t) + diurnal + wobble + precipMod(t);
    }

    /** Повільний дрейф між "якорями" з плавним переходом (з нульовою похідною в якорях). */
    private double front(long t) {
        double x = t / (double) ((long) DAY * smoothDays);
        long k = (long) Math.floor(x);
        double s = smooth(x - k);
        double a = anchor(k);
        double b = anchor(k + 1);
        return a + (b - a) * s;
    }

    private double anchor(long k) {
        double span = max - min;
        double lo = min + FRONT_LO * span;
        double hi = min + FRONT_HI * span;
        return lo + rnd(1, k, 0) * (hi - lo);
    }

    /** Вплив опадів на температуру: на початку відрізка плавно переходить від попереднього значення. */
    private double precipMod(long t) {
        long day = Math.floorDiv(t, (long) DAY);
        int tod = (int) Math.floorMod(t, (long) DAY);
        int idx = slotIndex(tod);
        double cur = mod(precip(day, idx));
        int from = slots.get(idx).from();
        int ramp = Math.min(1500, (slotEnd(idx) - from) / 2);
        int pos = tod - from;
        if (ramp <= 0 || pos >= ramp) {
            return cur;
        }
        long pd = day;
        int pi = idx - 1;
        if (pi < 0) {
            pd = day - 1;
            pi = slots.size() - 1;
        }
        double prev = mod(precip(pd, pi));
        return prev + (cur - prev) * smooth(pos / (double) ramp);
    }

    private double mod(Precip p) {
        return switch (p) {
            case SNOW -> snowMod;
            case BLIZZARD -> blizzardMod;
            default -> 0.0;
        };
    }

    // ---------- опади ----------

    public Precip precip(long day, int idx) {
        long start = slotStartAbs(day, idx);
        long end = slotEndAbs(day, idx);
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change c = changes.get(i);
            if (c.precip() != null && end > c.at() && start < c.until()) {
                return c.precip();
            }
        }
        Precip p = naturalPrecip(day, idx);
        // після примусової хуртовини не буває різкого "ясно": спершу сніг
        if (p == Precip.CLEAR) {
            for (Change c : changes) {
                if (c.precip() == Precip.BLIZZARD && start == c.until()) {
                    return Precip.SNOW;
                }
            }
        }
        return p;
    }

    private Precip naturalPrecip(long day, int idx) {
        double r = rnd(2, day, idx);
        if (r < blizzardChance) {
            return Precip.BLIZZARD;
        }
        if (r < blizzardChance + snowChance) {
            return Precip.SNOW;
        }
        return Precip.CLEAR;
    }

    // ---------- адмінські зміни ----------

    /**
     * Перебудовує прогноз уперед: за {@link #RAMP_IN} тіків температура сягає target (базова шкала),
     * далі зсув тримається пів доби й за кілька днів плавно повертається до природної кривої.
     * Опади (якщо задані) діють до кінця наступного відрізка доби.
     */
    public Change change(long now, double target, Precip forced) {
        target = clamp(target, min, max);
        long until = now;
        if (forced != null) {
            Entry e = at(now);
            long day = e.day();
            int next = e.index() + 1;
            if (next >= slots.size()) {
                next = 0;
                day++;
            }
            until = slotEndAbs(day, next);
        }
        prune(now);
        changes.add(new Change(now, 0.0, forced, until));
        // з урахуванням уже діючих змін і нових опадів: у момент now+RAMP_IN буде рівно target
        double off = target - (natural(now + RAMP_IN) + offsetAt(now + RAMP_IN));
        changes.set(changes.size() - 1, new Change(now, off, forced, until));
        return changes.get(changes.size() - 1);
    }

    public void reset() {
        changes.clear();
    }

    public List<Change> changes() {
        return List.copyOf(changes);
    }

    public void setChanges(Collection<Change> list) {
        changes.clear();
        changes.addAll(list);
    }

    private void prune(long now) {
        changes.removeIf(c -> now > Math.max(c.at() + HOLD + recoveryTicks, c.until()));
        while (changes.size() > 32) {
            changes.remove(0);
        }
    }

    private double offsetAt(long t) {
        double sum = 0.0;
        for (Change c : changes) {
            long dt = t - c.at();
            if (dt <= 0) {
                continue;
            }
            double in = smooth(dt / (double) RAMP_IN);
            double after = dt - HOLD;
            double out = after <= 0 ? 1.0 : 1.0 - smooth(after / (double) recoveryTicks);
            sum += c.offset() * in * out;
        }
        return sum;
    }

    // ---------- математика ----------

    private static double smooth(double x) {
        double v = Math.max(0.0, Math.min(1.0, x));
        return v * v * (3.0 - 2.0 * v);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Гладкий шум у діапазоні [-1; 1]. */
    private double noise(long salt, double x) {
        long k = (long) Math.floor(x);
        double s = smooth(x - k);
        double a = rnd(salt, k, 0) * 2.0 - 1.0;
        double b = rnd(salt, k + 1, 0) * 2.0 - 1.0;
        return a + (b - a) * s;
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private double rnd(long a, long b, long c) {
        long h = mix(seed ^ mix(a * 31 + 7) ^ mix(b * 131 + 17) ^ mix(c * 1009 + 29));
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    /** Тік доби -> "ГГ:ХХ" (тік 0 = 06:00). */
    public static String clock(int tick) {
        int h = Math.floorMod(tick / 1000 + 6, 24);
        int m = (int) ((tick % 1000) * 60L / 1000L);
        return String.format("%02d:%02d", h, m);
    }
}
