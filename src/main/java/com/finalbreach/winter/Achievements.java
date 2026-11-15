package com.finalbreach.winter;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Видача досягнень датапака fbwinter з коду плагіна. */
public final class Achievements {

    private static final Map<UUID, Integer> fuelAdds = new HashMap<>();

    private Achievements() {
    }

    public static void award(Player p, String id, String criterion) {
        Advancement adv = Bukkit.getAdvancement(new NamespacedKey("fbwinter", id));
        if (adv != null) {
            p.getAdvancementProgress(adv).awardCriteria(criterion);
        }
    }

    /** Гравець підкинув паливо в багаття; п'ятий раз — досягнення «Вогонь мій друг». */
    public static void countFuel(Player p) {
        int n = fuelAdds.merge(p.getUniqueId(), 1, Integer::sum);
        if (n >= 5) {
            award(p, "fire_friend", "done");
        }
    }
}
