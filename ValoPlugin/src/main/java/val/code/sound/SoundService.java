package val.code.sound;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import val.code.config.Settings;

import java.util.HashMap;
import java.util.Map;

public final class SoundService {

    private final JavaPlugin plugin;
    private final Settings settings;
    private final Map<String, SoundSpec> specifications = new HashMap<>();

    public SoundService(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        reload();
    }

    public void reload() {
        specifications.clear();

        var root = settings.raw().getConfigurationSection("sounds");

        if (root == null) {
            plugin.getLogger().warning("sounds 설정이 없습니다.");
            return;
        }

        for (String event : root.getKeys(false)) {
            String base = "sounds." + event + ".";

            boolean enabled = settings.bool(base + "enabled", true);
            String rawKey = settings.text(
                    base + "key", "minecraft:ui.button.click"
            );
            float volume = (float) settings.decimal(
                    base + "volume", 1.0, 0.0, 4.0
            );
            float pitch = (float) settings.decimal(
                    base + "pitch", 1.0, 0.1, 2.0
            );

            try {
                specifications.put(
                        event,
                        new SoundSpec(
                                enabled,
                                Key.key(rawKey),
                                volume,
                                pitch
                        )
                );
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(
                        "[사운드 " + event + "] 잘못된 키 '" + rawKey
                                + "'. 바닐라 클릭 사운드로 대체합니다."
                );

                specifications.put(
                        event,
                        new SoundSpec(
                                enabled,
                                Key.key("minecraft:ui.button.click"),
                                volume,
                                pitch
                        )
                );
            }
        }
    }

    public void play(Player player, String event) {
        SoundSpec spec = specifications.get(event);

        if (player == null || spec == null || !spec.enabled()) {
            return;
        }

        player.playSound(Sound.sound(
                spec.key(),
                Sound.Source.MASTER,
                spec.volume(),
                spec.pitch()
        ));
    }

    public void at(Location location, String event, double radius) {
        SoundSpec spec = specifications.get(event);

        if (spec == null
                || !spec.enabled()
                || location == null
                || location.getWorld() == null) {
            return;
        }

        double radiusSquared = radius * radius;

        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location)
                    <= radiusSquared) {
                player.playSound(
                        location,
                        spec.key().asString(),
                        spec.volume(),
                        spec.pitch()
                );
            }
        }
    }

    public void stop(Player player, String event) {
        SoundSpec spec = specifications.get(event);

        if (player != null && spec != null) {
            player.stopSound(Sound.sound(
                    spec.key(),
                    Sound.Source.MASTER,
                    spec.volume(),
                    spec.pitch()
            ));
        }
    }

    private record SoundSpec(
            boolean enabled,
            Key key,
            float volume,
            float pitch
    ) {
    }
}