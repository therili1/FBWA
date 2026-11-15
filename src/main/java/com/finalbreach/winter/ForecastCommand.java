package com.finalbreach.winter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /forecast                      - прогноз: сьогодні по відрізках доби і далі по днях
 * /forecast change <t> [опади]   - (адмін) змінити прогноз; наступні дні підлаштуються плавно
 * /forecast change reset         - (адмін) прибрати всі зміни
 * /forecast here                 - (адмін) що відчуває гравець: надворі, укриття, тепло від вогню
 * /forecast override <t|off>     - (адмін) тестова фіксована температура для всіх
 * /forecast reload               - (адмін) перечитати config.yml
 */
public final class ForecastCommand implements TabExecutor {

    private static final String ADMIN = "finalbreach.winter.admin";
    private static final List<String> PRECIP = List.of("clear", "snow", "blizzard");

    private final WinterPlugin plugin;

    public ForecastCommand(WinterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload", "override", "change", "here", "storm" -> {
                    if (!sender.hasPermission(ADMIN)) {
                        sender.sendMessage(Component.text("Немає прав.", NamedTextColor.RED));
                        return true;
                    }
                    switch (sub) {
                        case "reload" -> reload(sender);
                        case "override" -> override(sender, args);
                        case "change" -> change(sender, args);
                        case "storm" -> storm(sender, args);
                        default -> here(sender);
                    }
                    return true;
                }
                default -> {
                    // невідомий підпункт: показуємо прогноз
                }
            }
        }

        World w = targetWorld(sender);
        if (w == null) {
            sender.sendMessage(Component.text("У цьому світі зимової системи немає.", NamedTextColor.GRAY));
            return true;
        }
        show(sender, w);
        return true;
    }

    private World targetWorld(CommandSender sender) {
        if (sender instanceof Player p) {
            return plugin.isManaged(p.getWorld()) ? p.getWorld() : null;
        }
        for (World candidate : Bukkit.getWorlds()) {
            if (plugin.isManaged(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ---------- адмінські підкоманди ----------

    private void reload(CommandSender sender) {
        plugin.reloadAll();
        sender.sendMessage(Component.text("Конфіг перезавантажено.", NamedTextColor.GREEN));
    }

    private void override(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Використання: /forecast override <температура|off>", NamedTextColor.RED));
            return;
        }
        if (args[1].equalsIgnoreCase("off")) {
            plugin.setOverrideTemp(null);
            sender.sendMessage(Component.text("Тестову температуру вимкнено.", NamedTextColor.GREEN));
            return;
        }
        Double t = parse(args[1]);
        if (t == null) {
            sender.sendMessage(Component.text("Це не число: " + args[1], NamedTextColor.RED));
            return;
        }
        plugin.setOverrideTemp(t);
        sender.sendMessage(Component.text("Тестова температура: " + t + "°C для всіх гравців.", NamedTextColor.YELLOW));
    }

    private void storm(CommandSender sender, String[] args) {
        World w = targetWorld(sender);
        if (w == null) {
            sender.sendMessage(Component.text("У цьому світі зимової системи немає.", NamedTextColor.GRAY));
            return;
        }
        Climate c = plugin.climate(w);
        if (args.length < 2) {
            sender.sendMessage(Component.text("Використання: /forecast storm <on|off|status>", NamedTextColor.RED));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                c.setForcedStorm(true);
                plugin.afterChange(w);
                sender.sendMessage(Component.text("Хуртовина ввімкнена, діє поки не вимкнеш.", NamedTextColor.AQUA));
            }
            case "off" -> {
                c.setForcedStorm(false);
                plugin.afterChange(w);
                sender.sendMessage(Component.text("Хуртовину вимкнено, погода повертається за прогнозом.", NamedTextColor.GREEN));
            }
            case "status" -> sender.sendMessage(Component.text(
                    "Примусова хуртовина: " + (c.forcedStorm() ? "увімкнена" : "вимкнена"), NamedTextColor.GRAY));
            default -> sender.sendMessage(Component.text("Використання: /forecast storm <on|off|status>", NamedTextColor.RED));
        }
    }

    private void change(CommandSender sender, String[] args) {
        World w = targetWorld(sender);
        if (w == null) {
            sender.sendMessage(Component.text("У цьому світі зимової системи немає.", NamedTextColor.GRAY));
            return;
        }
        String usage = "Використання: /forecast change <температура|reset> [clear|snow|blizzard]";
        if (args.length < 2) {
            sender.sendMessage(Component.text(usage, NamedTextColor.RED));
            return;
        }
        Climate c = plugin.climate(w);

        if (args[1].equalsIgnoreCase("reset")) {
            c.reset();
            plugin.afterChange(w);
            sender.sendMessage(Component.text("Усі зміни прогнозу прибрано.", NamedTextColor.GREEN));
            return;
        }

        Double target = parse(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Це не число: " + args[1], NamedTextColor.RED));
            return;
        }
        Climate.Precip precip = null;
        if (args.length >= 3) {
            precip = switch (args[2].toLowerCase(Locale.ROOT)) {
                case "clear", "ясно" -> Climate.Precip.CLEAR;
                case "snow", "сніг" -> Climate.Precip.SNOW;
                case "blizzard", "хуртовина" -> Climate.Precip.BLIZZARD;
                default -> null;
            };
            if (precip == null) {
                sender.sendMessage(Component.text(usage, NamedTextColor.RED));
                return;
            }
        }

        c.change(w.getFullTime(), target, precip);
        plugin.afterChange(w);

        Settings s = plugin.settings();
        double shown = Math.max(s.tMin, Math.min(s.tMax, target));
        Component msg = Component.text("Прогноз змінено: ", NamedTextColor.GREEN)
                .append(Component.text(fmt(shown) + (precip != null ? ", " + precip.label : ""), NamedTextColor.YELLOW))
                .append(Component.text(" (базова шкала). Далі погода плавно повернеться до звичайної.", NamedTextColor.GREEN));
        sender.sendMessage(msg);
        if (sender instanceof Player p) {
            double here = c.map(shown, plugin.profile(p.getLocation()));
            sender.sendMessage(Component.text("У вашому біомі це приблизно " + fmt(here) + ".", NamedTextColor.GRAY));
        }
    }

    private void here(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Тільки для гравця.", NamedTextColor.RED));
            return;
        }
        HeatField.Local l = plugin.heat().local(p);
        if (l == null) {
            sender.sendMessage(Component.text("Тут зимової системи немає (або дані ще не пораховані).", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("Температура навколо вас", NamedTextColor.AQUA, TextDecoration.BOLD));
        sender.sendMessage(Component.text(String.format(Locale.US, " Надворі: %.1f°C", l.outside()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(String.format(Locale.US, " Укриття (стіни, дах, глибина): +%.1f°", l.bonus()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(String.format(Locale.US, " Тепло від джерел: +%.1f° (%d шт.)", l.heat(), l.sources()),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(String.format(Locale.US, " Разом тут: %.1f°C", l.temp()), NamedTextColor.GOLD));
    }

    // ---------- прогноз ----------

    private void show(CommandSender s, World w) {
        Settings cfg = plugin.settings();
        Climate c = plugin.climate(w);
        Location where = s instanceof Player p ? p.getLocation() : null;
        Settings.Profile prof = where != null ? plugin.profile(where) : cfg.baseProfile();

        long now = w.getFullTime();
        Climate.Entry cur = c.at(now);
        double temp = plugin.overrideTemp() != null ? plugin.overrideTemp() : c.temp(now, prof);

        s.sendMessage(Component.text("Прогноз погоди", NamedTextColor.AQUA, TextDecoration.BOLD));
        s.sendMessage(Component.text("Зараз: ", NamedTextColor.GRAY)
                .append(Component.text(fmt(temp), tempColor(temp)))
                .append(Component.text("  "))
                .append(precipText(cur.precip(), true)));
        if (plugin.overrideTemp() != null) {
            s.sendMessage(Component.text("Увімкнена тестова температура (/forecast override off, щоб вимкнути).",
                    NamedTextColor.DARK_GRAY));
        }

        if (cur.index() + 1 < c.slotCount()) {
            s.sendMessage(Component.text("Сьогодні далі:", NamedTextColor.GOLD));
            for (int i = cur.index() + 1; i < c.slotCount(); i++) {
                Climate.Entry e = c.entry(cur.day(), i);
                double[] r = c.range(cur.day() * Climate.DAY + e.fromTick(), cur.day() * Climate.DAY + e.toTick(), prof, 4);
                s.sendMessage(Component.text(" " + Climate.clock(e.fromTick()) + "–" + Climate.clock(e.toTick())
                                + " " + e.name() + ": ", NamedTextColor.GRAY)
                        .append(rangeText(r))
                        .append(Component.text("  "))
                        .append(precipText(e.precip(), true)));
            }
        }

        s.sendMessage(Component.text("Наступні дні:", NamedTextColor.GOLD));
        for (int d = 1; d < cfg.forecastDays; d++) {
            long day = cur.day() + d;
            double[] r = c.range(day * Climate.DAY, (day + 1) * Climate.DAY, prof, 12);
            Component icons = Component.empty();
            for (int i = 0; i < c.slotCount(); i++) {
                icons = icons.append(precipText(c.entry(day, i).precip(), false)).append(Component.text(" "));
            }
            String label = d == 1 ? "Завтра" : "+" + d + " д.";
            s.sendMessage(Component.text(" " + label + "  ", NamedTextColor.GRAY)
                    .append(rangeText(r))
                    .append(Component.text("   "))
                    .append(icons));
        }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < c.slotCount(); i++) {
            names.append(i == 0 ? "" : " · ").append(c.entry(cur.day(), i).name().toLowerCase(Locale.ROOT));
        }
        s.sendMessage(Component.text("○ ясно  ❄ сніг  ✷ хуртовина   (" + names + ")", NamedTextColor.DARK_GRAY));
    }

    private Component rangeText(double[] r) {
        long lo = Math.round(r[0]);
        long hi = Math.round(r[1]);
        String text = lo == hi ? lo + "°C" : lo + "…" + hi + "°C";
        return Component.text(text, tempColor(r[0]));
    }

    private Component precipText(Climate.Precip p, boolean withLabel) {
        TextColor color = switch (p) {
            case CLEAR -> NamedTextColor.GRAY;
            case SNOW -> NamedTextColor.AQUA;
            case BLIZZARD -> NamedTextColor.DARK_AQUA;
        };
        return Component.text(withLabel ? p.symbol + " " + p.label : p.symbol, color);
    }

    private static String fmt(double t) {
        return Math.round(t) + "°C";
    }

    /** Колір за небезпекою: чим холодніше, тим червоніше. */
    private TextColor tempColor(double t) {
        double c = plugin.settings().coldFactor(t);
        if (c >= 0.85) {
            return NamedTextColor.RED;
        }
        if (c >= 0.55) {
            return NamedTextColor.GOLD;
        }
        if (c >= 0.25) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.GREEN;
    }

    private static Double parse(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.').replace('−', '-'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------- підказки ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(ADMIN)) {
            return out;
        }
        if (args.length == 1) {
            for (String o : List.of("change", "here", "override", "reload", "storm")) {
                if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("override")) {
            out.add("off");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("storm")) {
            out.addAll(List.of("on", "off", "status"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("change")) {
            out.addAll(List.of("reset", "-25", "-15", "-5", "0"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("change")) {
            for (String o : PRECIP) {
                if (o.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
