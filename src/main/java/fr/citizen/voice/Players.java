package fr.citizen.voice;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Position de chaque joueur connecté, recopiée à chaque tick par le thread serveur.
 * Les threads audio lisent ces copies sans jamais toucher au monde Minecraft.
 */
public final class Players {
    public record Snap(UUID uuid, String name, String level, double x, double y, double z, float yaw, boolean spectator) {
        public double distTo(Snap o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private static final Map<UUID, Snap> SNAPS = new ConcurrentHashMap<>();

    private Players() {
    }

    public static void put(Snap s) {
        SNAPS.put(s.uuid(), s);
    }

    public static void remove(UUID uuid) {
        SNAPS.remove(uuid);
    }

    public static void clear() {
        SNAPS.clear();
    }

    public static Snap get(UUID uuid) {
        return SNAPS.get(uuid);
    }

    public static Collection<Snap> all() {
        return SNAPS.values();
    }
}
