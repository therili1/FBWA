package com.finalbreach.winter;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Позначки на снігу, що переживають рестарт (зберігаються в даних чанка):
 *  - ours: блоки снігу, які склав сам плагін (їх можна розібрати назад у шари);
 *  - player: сніг, поставлений гравцями (плагін його не чіпає: не додає й не топить).
 */
public final class SnowMarks {

    private record Key(UUID world, long chunk) {}

    private static final class Entry {
        final Set<Integer> ours = new HashSet<>();
        final Set<Integer> player = new HashSet<>();
        boolean dirty;
        Chunk chunk;
    }

    private final NamespacedKey keyOurs;
    private final NamespacedKey keyPlayer;
    private final Map<Key, Entry> cache = new HashMap<>();

    public SnowMarks(Plugin plugin) {
        keyOurs = new NamespacedKey(plugin, "snow_ours");
        keyPlayer = new NamespacedKey(plugin, "snow_player");
    }

    public boolean isOurs(Block b) {
        return entry(b).ours.contains(local(b));
    }

    public boolean isPlayer(Block b) {
        return entry(b).player.contains(local(b));
    }

    public void setOurs(Block b, boolean on) {
        Entry e = entry(b);
        if (on ? e.ours.add(local(b)) : e.ours.remove(local(b))) {
            e.dirty = true;
        }
    }

    public void setPlayer(Block b, boolean on) {
        Entry e = entry(b);
        if (on ? e.player.add(local(b)) : e.player.remove(local(b))) {
            e.dirty = true;
        }
    }

    /** Стерти будь-які позначки з цієї клітинки. */
    public void clear(Block b) {
        setOurs(b, false);
        setPlayer(b, false);
    }

    /** Чанк вивантажується: записуємо й забуваємо. */
    public void flush(Chunk ch) {
        Entry e = cache.remove(new Key(ch.getWorld().getUID(), ch.getChunkKey()));
        if (e != null && e.dirty) {
            e.chunk = ch;
            write(e);
        }
    }

    public void flushDirty() {
        for (Entry e : cache.values()) {
            if (e.dirty) {
                write(e);
            }
        }
    }

    public void flushAll() {
        flushDirty();
        cache.clear();
    }

    // ---------- внутрішнє ----------

    private Entry entry(Block b) {
        Chunk ch = b.getChunk();
        return cache.computeIfAbsent(new Key(ch.getWorld().getUID(), ch.getChunkKey()), k -> load(ch));
    }

    private Entry load(Chunk ch) {
        Entry e = new Entry();
        e.chunk = ch;
        PersistentDataContainer pdc = ch.getPersistentDataContainer();
        read(pdc, keyOurs, e.ours);
        read(pdc, keyPlayer, e.player);
        return e;
    }

    private static void read(PersistentDataContainer pdc, NamespacedKey key, Set<Integer> into) {
        long[] a = pdc.get(key, PersistentDataType.LONG_ARRAY);
        if (a != null) {
            for (long v : a) {
                into.add((int) v);
            }
        }
    }

    private void write(Entry e) {
        PersistentDataContainer pdc = e.chunk.getPersistentDataContainer();
        put(pdc, keyOurs, e.ours);
        put(pdc, keyPlayer, e.player);
        e.dirty = false;
    }

    private static void put(PersistentDataContainer pdc, NamespacedKey key, Set<Integer> set) {
        if (set.isEmpty()) {
            pdc.remove(key);
            return;
        }
        long[] a = new long[set.size()];
        int i = 0;
        for (int v : set) {
            a[i++] = v;
        }
        pdc.set(key, PersistentDataType.LONG_ARRAY, a);
    }

    /** Позиція всередині чанка: y (з зсувом) + x + z. */
    private static int local(Block b) {
        return ((b.getY() + 2048) << 8) | ((b.getX() & 15) << 4) | (b.getZ() & 15);
    }
}
