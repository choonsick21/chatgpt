package val.code.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class Settings {

    private final JavaPlugin plugin;

    public Settings(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public FileConfiguration raw() {
        return plugin.getConfig();
    }

    public int integer(String path, int fallback, int minimum, int maximum) {
        Object value = raw().get(path);
        int result;

        if (value == null) {
            result = fallback;
        } else if (value instanceof Number number) {
            result = number.intValue();
        } else {
            warn(path, "정수가 아니므로 기본값 " + fallback + "을 사용합니다.");
            result = fallback;
        }

        int clamped = Math.max(minimum, Math.min(maximum, result));

        if (clamped != result) {
            warn(path, "허용 범위 " + minimum + "~" + maximum
                    + " 밖의 값 " + result + "을 " + clamped + "로 보정합니다.");
        }

        return clamped;
    }

    public double decimal(
            String path,
            double fallback,
            double minimum,
            double maximum
    ) {
        Object value = raw().get(path);
        double result;

        if (value == null) {
            result = fallback;
        } else if (value instanceof Number number) {
            result = number.doubleValue();
        } else {
            warn(path, "숫자가 아니므로 기본값 " + fallback + "을 사용합니다.");
            result = fallback;
        }

        if (!Double.isFinite(result)) {
            warn(path, "유한한 숫자가 아니므로 기본값을 사용합니다.");
            result = fallback;
        }

        double clamped = Math.max(minimum, Math.min(maximum, result));

        if (clamped != result) {
            warn(path, "허용 범위 밖의 값을 " + clamped + "로 보정합니다.");
        }

        return clamped;
    }

    public boolean bool(String path, boolean fallback) {
        Object value = raw().get(path);

        if (value == null) {
            return fallback;
        }

        if (value instanceof Boolean bool) {
            return bool;
        }

        warn(path, "true/false가 아니므로 기본값을 사용합니다.");
        return fallback;
    }

    public String text(String path, String fallback) {
        String value = raw().getString(path);
        return value == null || value.isBlank() ? fallback : value;
    }

    public int ticks(String secondsPath, int fallbackSeconds) {
        return integer(secondsPath, fallbackSeconds, 1, 3600) * 20;
    }

    public void validate() {
        int max = integer("rooms.maximum-capacity", 10, 2, 20);
        integer("rooms.default-capacity", 10, 2, max);
        integer("rooms.minimum-players", 2, 2, max);
        integer("rooms.maximum-rooms", 40, 1, 500);

        integer("selection.loading-seconds", 3, 1, 60);
        integer("selection.timeout-seconds", 30, 1, 600);
        integer("selection.countdown-seconds", 5, 1, 30);

        integer("match.buy-seconds", 25, 1, 300);
        integer("match.round-seconds", 100, 10, 3600);
        integer("match.target-wins", 7, 1, 100);
        integer("match.swap-after-rounds", 6, 1, 199);

        int moneyMaximum = integer(
                "economy.maximum-money", 9000, 0, 1_000_000
        );
        integer("economy.starting-money", 800, 0, moneyMaximum);

        integer("classic.magazine", 12, 1, 100);
        integer("classic.reserve-ammo", 36, 0, 1000);
        integer("classic.fire-interval-ticks", 5, 1, 100);
        integer("classic.reload-ticks", 30, 1, 1200);
        decimal("classic.maximum-range", 70, 1, 256);

        double near = decimal(
                "classic.damage.near-distance", 15, 0, 256
        );
        double middle = decimal(
                "classic.damage.middle-distance", 30, 0, 256
        );

        if (middle < near) {
            warn("classic.damage.middle-distance",
                    "중거리 경계가 근거리보다 작습니다. 전투 서비스에서 근거리 이상으로 보정합니다.");
        }

        integer("jett.smoke.surface-particles", 180, 24, 600);
        integer("jett.smoke.interior-particles", 24, 0, 100);
        integer("jett.smoke.maximum-active-per-match", 8, 1, 16);
        integer("jett.smoke.update-interval-ticks", 4, 2, 20);
        decimal("jett.smoke.radius", 2.8, 1, 6);
    }

    private void warn(String path, String message) {
        plugin.getLogger().warning("[설정] " + path + ": " + message);
    }
}