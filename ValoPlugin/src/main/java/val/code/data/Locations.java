package val.code.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.UUID;

public final class Locations {

    private Locations() {
    }

    public static void write(
            ConfigurationSection parent,
            String path,
            Location location
    ) {
        parent.set(path, null);

        if (location == null || location.getWorld() == null) {
            return;
        }

        ConfigurationSection section = parent.createSection(path);

        section.set("world", location.getWorld().getName());
        section.set("world-id", location.getWorld().getUID().toString());
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", location.getYaw());
        section.set("pitch", location.getPitch());
    }

    public static Location read(
            ConfigurationSection parent,
            String path
    ) {
        ConfigurationSection section =
                parent.getConfigurationSection(path);

        if (section == null) {
            return null;
        }

        World world;
        String worldId = section.getString("world-id");

        if (worldId != null && !worldId.isBlank()) {
            UUID id;

            try {
                id = UUID.fromString(worldId);
            } catch (IllegalArgumentException exception) {
                return null;
            }

            world = Bukkit.getWorld(id);

            // UUID가 저장된 데이터는 다른 UUID의 동명 월드로 대체하지 않습니다.
            if (world == null) {
                return null;
            }
        } else {
            String worldName = section.getString("world");

            if (worldName == null || worldName.isBlank()) {
                return null;
            }

            world = Bukkit.getWorld(worldName);

            if (world == null) {
                return null;
            }
        }

        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw");
        float pitch = (float) section.getDouble("pitch");

        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Float.isFinite(yaw)
                || !Float.isFinite(pitch)) {
            return null;
        }

        return new Location(world, x, y, z, yaw, pitch);
    }

    public static boolean sameWorld(
            Location first,
            Location second
    ) {
        return first != null
                && second != null
                && first.getWorld() != null
                && second.getWorld() != null
                && first.getWorld().getUID()
                .equals(second.getWorld().getUID());
    }
}