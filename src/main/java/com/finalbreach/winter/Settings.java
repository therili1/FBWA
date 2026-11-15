package com.finalbreach.winter;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class Settings {

    /** Діапазон температур біому. */
    public record Profile(double min, double max) {}

    /** Джерело тепла: power - потужність обігріву приміщення, radiant - тепло поруч з вогнем. */
    public record Source(double power, double radiant) {}

    private static final List<Climate.Slot> DEFAULT_SLOTS = List.of(
            new Climate.Slot("Ранок", 0),
            new Climate.Slot("День", 6000),
            new Climate.Slot("Вечір", 12000),
            new Climate.Slot("Ніч", 18000));

    // клімат
    public final Set<String> worlds;
    public final double tMin;
    public final double tMax;
    public final int smoothDays;
    public final double swing;
    public final double comfort;
    public final List<Climate.Slot> slots;
    public final int forecastDays;
    public final double recoveryDays;

    // біоми
    public final double coldBelow;
    public final double warmFrom;
    public final Profile coldProfile;
    public final Profile temperateProfile;
    public final Profile warmProfile;
    public final Map<String, Profile> biomeOverrides;

    // опади
    public final boolean controlWeather;
    public final double snowChance;
    public final double blizzardChance;
    public final double snowMod;
    public final double blizzardMod;
    public final double forcedBlizzardMod;

    // холод
    public final double grace;
    public final double baseInterval;
    public final double minInterval;
    public final double maxInterval;
    public final double damage;
    public final boolean frost;
    public final boolean actionbar;
    public final double safeAbove;
    public final double feelWarm;
    public final double feelCool;
    public final double hungerRate;
    public final boolean slowdown;
    public final double slowStage;

    // мокрий одяг
    public final boolean wetEnabled;
    public final double wetSeconds;
    public final double wetPenalty;
    public final double wetHeatDry;

    // теплиця
    public final boolean greenhouse;
    public final double growMin;
    public final double growGood;

    // укриття і тепло
    public final double shelterBonus;
    public final double caveBonus;
    public final int heatEvery;
    public final double warmup;
    public final double cooldown;
    public final double sealedCooldown;
    public final int heatDistance;
    public final int heatCells;
    public final double heatDecay;
    public final double reachPerFurnace;
    public final Map<Material, Source> sources;

    // багаття
    public final boolean campfireUnlit;
    public final int fuelCap;
    public final int stickSeconds;
    public final int coalSeconds;
    public final int unknownLitSeconds;
    public final int exposedBurn;

    // сніг
    public final boolean snowEnabled;
    public final int maxLayers;
    public final int snowRadius;
    public final int snowSamples;
    public final double blizzardMult;
    public final int meltSamples;
    public final boolean snowParticles;
    public final int stormParticles;
    public final double stormRadius;

    // грілки
    public final Set<Material> conductors = new HashSet<>();
    public final int heaterSeconds;
    public final int magmaHeaterSeconds;
    public final int loreUpdate;
    public final boolean discoverRecipe;

    private Settings(FileConfiguration c, Logger log) {
        List<String> w = c.getStringList("worlds");
        worlds = new HashSet<>(w.isEmpty() ? List.of("world") : w);

        double mn = c.getDouble("temperature.min", -25);
        double mx = c.getDouble("temperature.max", 2);
        if (mn >= mx) {
            log.warning("temperature.min має бути меншим за temperature.max, беру -25 і 2");
            mn = -25;
            mx = 2;
        }
        tMin = mn;
        tMax = mx;
        smoothDays = Math.max(1, c.getInt("temperature.smooth-days", 3));
        swing = Math.max(0, c.getDouble("temperature.daily-swing", 3));
        comfort = c.getDouble("temperature.comfort", 22);
        slots = parseSlots(c, log);
        forecastDays = Math.max(2, Math.min(14, c.getInt("forecast.days", 9)));
        recoveryDays = Math.max(0.5, c.getDouble("forecast.change-recovery-days", 3));

        coldBelow = c.getDouble("biomes.cold-below", 0.35);
        warmFrom = c.getDouble("biomes.warm-from", 0.9);
        coldProfile = new Profile(c.getDouble("biomes.cold-min", -32), mx);
        temperateProfile = new Profile(c.getDouble("biomes.temperate-min", -22), mx);
        warmProfile = new Profile(c.getDouble("biomes.warm-min", -18), mx);
        biomeOverrides = new HashMap<>();
        ConfigurationSection ov = c.getConfigurationSection("biomes.overrides");
        if (ov != null) {
            for (String key : ov.getKeys(false)) {
                biomeOverrides.put(key, new Profile(ov.getDouble(key), mx));
            }
        }

        controlWeather = c.getBoolean("precipitation.control-weather", true);
        snowChance = c.getDouble("precipitation.snow-chance", 0.30);
        blizzardChance = c.getDouble("precipitation.blizzard-chance", 0.10);
        snowMod = c.getDouble("precipitation.snow-temp-modifier", -1);
        blizzardMod = c.getDouble("precipitation.blizzard-temp-modifier", -4);
        forcedBlizzardMod = c.getDouble("precipitation.forced-blizzard-temp-modifier", -8);

        grace = Math.max(0, c.getDouble("cold.grace-seconds", 15));
        baseInterval = Math.max(0.5, c.getDouble("cold.base-interval-seconds", 10.0));
        minInterval = Math.max(0.5, c.getDouble("cold.min-interval-seconds", 1.0));
        maxInterval = Math.max(minInterval, c.getDouble("cold.max-interval-seconds", 20.0));
        damage = Math.max(0, c.getDouble("cold.damage", 1.0));
        frost = c.getBoolean("cold.frost-overlay", true);
        actionbar = c.getBoolean("cold.actionbar", true);
        safeAbove = Math.max(mn + 1, c.getDouble("cold.safe-above", 18));
        feelWarm = Math.max(0.5, c.getDouble("cold.feel-warm-seconds", 6));
        feelCool = Math.max(0.5, c.getDouble("cold.feel-cool-seconds", 12));
        hungerRate = Math.max(0, c.getDouble("cold.hunger-exhaustion-per-second", 0.08));
        slowdown = c.getBoolean("cold.slowdown", true);
        slowStage = Math.max(0.0, Math.min(1.0, c.getDouble("cold.slowdown-stage", 0.75)));

        wetEnabled = c.getBoolean("wet.enabled", true);
        wetSeconds = Math.max(5, c.getDouble("wet.seconds", 45));
        wetPenalty = Math.max(0, c.getDouble("wet.penalty", 1.5));
        wetHeatDry = Math.max(0, c.getDouble("wet.dry-near-heat", 0.5));

        greenhouse = c.getBoolean("greenhouse.enabled", true);
        growMin = c.getDouble("greenhouse.min-temp", 3);
        growGood = Math.max(growMin + 1, c.getDouble("greenhouse.good-temp", 15));

        shelterBonus = Math.max(0, c.getDouble("shelter.max-bonus", 4));
        caveBonus = Math.max(0, c.getDouble("shelter.cave-bonus", 14));
        heatEvery = Math.max(1, c.getInt("heat.update-seconds", 2));
        warmup = Math.max(1, c.getDouble("heat.warmup-seconds", 30));
        cooldown = Math.max(1, c.getDouble("heat.cooldown-seconds", 40));
        sealedCooldown = Math.max(cooldown, c.getDouble("heat.sealed-cooldown-seconds", 1200));
        heatDistance = Math.max(3, Math.min(24, c.getInt("heat.max-distance", 12)));
        heatCells = Math.max(50, Math.min(3000, c.getInt("heat.max-cells", 700)));
        heatDecay = Math.max(1.0, Math.min(40.0, c.getDouble("heat.decay-blocks", 10.0)));
        reachPerFurnace = Math.max(0, c.getDouble("heat.reach-per-furnace", 3));
        sources = parseSources(c, log);

        campfireUnlit = c.getBoolean("campfire.place-unlit", true);
        fuelCap = Math.max(10, c.getInt("campfire.fuel-cap-seconds", 600));
        stickSeconds = Math.max(1, c.getInt("campfire.stick-seconds", 30));
        coalSeconds = Math.max(1, c.getInt("campfire.coal-seconds", 180));
        unknownLitSeconds = Math.max(1, c.getInt("campfire.unknown-lit-seconds", 300));
        exposedBurn = Math.max(1, c.getInt("campfire.exposed-burn-multiplier", 3));

        snowEnabled = c.getBoolean("snow.enabled", true);
        maxLayers = Math.max(1, Math.min(16, c.getInt("snow.max-layers", 10)));
        snowRadius = Math.max(8, Math.min(64, c.getInt("snow.radius", 32)));
        snowSamples = Math.max(0, c.getInt("snow.samples-per-second", 60));
        blizzardMult = Math.max(1.0, c.getDouble("snow.blizzard-multiplier", 2.5));
        meltSamples = Math.max(0, c.getInt("snow.melt-samples-per-second", 30));
        snowParticles = c.getBoolean("snow.particles", true);
        stormParticles = Math.max(1, Math.min(300, c.getInt("snow.storm-particles", 120)));
        stormRadius = Math.max(3.0, Math.min(40.0, c.getDouble("snow.storm-radius", 24.0)));
        for (String name : c.getStringList("heat.conductors")) {
            Material mat = Material.matchMaterial(name);
            if (mat != null) {
                conductors.add(mat);
            }
        }

        heaterSeconds = Math.max(10, c.getInt("heater.duration-seconds", 600));
        magmaHeaterSeconds = Math.max(10, c.getInt("heater.magma-duration-seconds", 1800));
        loreUpdate = Math.max(1, c.getInt("heater.lore-update-seconds", 30));
        discoverRecipe = c.getBoolean("heater.discover-recipe-on-join", true);
    }

    public static Settings load(FileConfiguration c, Logger log) {
        return new Settings(c, log);
    }

    /** Діапазон температур біому: за назвою біому або за його ванільною теплотою. */
    public Profile profile(String biomeKey, double vanillaTemp) {
        Profile o = biomeOverrides.get(biomeKey);
        if (o != null) {
            return o;
        }
        if (vanillaTemp < coldBelow) {
            return coldProfile;
        }
        if (vanillaTemp < warmFrom) {
            return temperateProfile;
        }
        return warmProfile;
    }

    public Profile baseProfile() {
        return new Profile(tMin, tMax);
    }

    /** 0 = безпечно, 1 = найлютіший мороз. */
    public double coldFactor(double temp) {
        double c = (safeAbove - temp) / (safeAbove - tMin);
        return Math.max(0.0, Math.min(1.0, c));
    }

    /** Скільки "очок тепла" треба: 0 (безпечно) або від 0.5 до 4. */
    public double requiredWarmth(double temp) {
        if (temp >= safeAbove) {
            return 0.0;
        }
        return 0.5 + 3.5 * coldFactor(temp);
    }

    private static Map<Material, Source> parseSources(FileConfiguration c, Logger log) {
        Map<Material, Source> out = new EnumMap<>(Material.class);
        ConfigurationSection sec = c.getConfigurationSection("heat.sources");
        if (sec == null) {
            out.put(Material.FURNACE, new Source(450, 5));
            out.put(Material.BLAST_FURNACE, new Source(520, 6));
            out.put(Material.SMOKER, new Source(380, 4));
            out.put(Material.CAMPFIRE, new Source(220, 9));
            out.put(Material.SOUL_CAMPFIRE, new Source(200, 8));
            return out;
        }
        for (String key : sec.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            ConfigurationSection e = sec.getConfigurationSection(key);
            if (m == null || e == null) {
                log.warning("heat.sources: не розумію запис '" + key + "', пропускаю");
                continue;
            }
            out.put(m, new Source(Math.max(0, e.getDouble("power", 0)), Math.max(0, e.getDouble("radiant", 0))));
        }
        return out;
    }

    private static List<Climate.Slot> parseSlots(FileConfiguration c, Logger log) {
        List<Climate.Slot> list = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("slots")) {
            Object name = m.get("name");
            Object from = m.get("from");
            if (from instanceof Number fn) {
                list.add(new Climate.Slot(name == null ? "Відрізок" : name.toString(), fn.intValue()));
            }
        }
        list.sort(Comparator.comparingInt(Climate.Slot::from));

        boolean ok = !list.isEmpty() && list.get(0).from() == 0;
        for (int i = 0; ok && i < list.size(); i++) {
            int f = list.get(i).from();
            if (f < 0 || f >= Climate.DAY || (i > 0 && f == list.get(i - 1).from())) {
                ok = false;
            }
        }
        if (!ok) {
            log.warning("slots у config.yml некоректні (перший from має бути 0, значення різні й менші за 24000). Беру типові.");
            return DEFAULT_SLOTS;
        }
        return List.copyOf(list);
    }
}
