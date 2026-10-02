package val.code.map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import val.code.data.Locations;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MapRepository {

    private final JavaPlugin plugin;
    private final File file;

    private final Map<String, ArenaMap> maps = new LinkedHashMap<>();
    private final Map<String, UUID> reservations = new HashMap<>();

    public MapRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "maps.yml");
    }

    public void load() {
        requireMainThread();
        maps.clear();
        reservations.clear();

        if (!file.exists()) {
            plugin.saveResource("maps.yml", false);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("maps");

        if (root == null) {
            return;
        }

        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);

            if (section == null) {
                continue;
            }

            try {
                String id = normalizeId(rawId);
                ArenaMap map = new ArenaMap(id);

                map.displayName(section.getString("display-name", id));

                String world = section.getString("world");
                if (world != null && !world.isBlank()) {
                    map.worldName(world);
                }

                map.enabled(section.getBoolean("enabled", false));
                map.pos1(Locations.read(section, "bounds.pos1"));
                map.pos2(Locations.read(section, "bounds.pos2"));
                map.spectator(Locations.read(section, "spectator"));

                map.siteA1(Locations.read(section, "sites.a.pos1"));
                map.siteA2(Locations.read(section, "sites.a.pos2"));
                map.siteB1(Locations.read(section, "sites.b.pos1"));
                map.siteB2(Locations.read(section, "sites.b.pos2"));

                loadSpawns(section, "spawns.attack", map, true);
                loadSpawns(section, "spawns.defense", map, false);

                maps.put(id, map);

                for (String problem : map.problems(false)) {
                    plugin.getLogger().warning(
                            "[맵 " + id + "] " + problem
                    );
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(
                        "[맵 로드] " + rawId + ": " + exception.getMessage()
                );
            }
        }

        plugin.getLogger().info("맵 " + maps.size() + "개를 불러왔습니다.");
    }

    public Collection<ArenaMap> all() {
        return maps.values().stream()
                .sorted(Comparator.comparing(ArenaMap::id))
                .toList();
    }

    public ArenaMap find(String id) {
        if (id == null) {
            return null;
        }
        return maps.get(id.toLowerCase(Locale.ROOT));
    }

    public ArenaMap require(String id) {
        ArenaMap map = find(id);

        if (map == null) {
            throw new IllegalArgumentException("등록되지 않은 맵입니다: " + id);
        }

        return map;
    }

    public ArenaMap create(String rawId) {
        requireMainThread();

        String id = normalizeId(rawId);

        if (maps.containsKey(id)) {
            throw new IllegalArgumentException("이미 존재하는 맵 ID입니다.");
        }

        ArenaMap map = new ArenaMap(id);
        maps.put(id, map);
        save();
        return map;
    }

    public void delete(String id) {
        requireMainThread();

        ArenaMap map = require(id);
        requireEditable(map.id());
        maps.remove(map.id());
        save();
    }

    public boolean isReserved(String id) {
        return reservations.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public UUID reservation(String id) {
        return reservations.get(id.toLowerCase(Locale.ROOT));
    }

    public void requireEditable(String id) {
        if (isReserved(id)) {
            throw new IllegalArgumentException(
                    "현재 경기가 사용 중인 맵은 수정하거나 삭제할 수 없습니다."
            );
        }
    }

    public void reserve(String id, UUID matchId) {
        requireMainThread();

        ArenaMap map = require(id);
        List<String> problems = map.problems(true);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "맵 설정이 완료되지 않았습니다:\n- "
                            + String.join("\n- ", problems)
            );
        }

        if (reservations.putIfAbsent(map.id(), matchId) != null) {
            throw new IllegalArgumentException(
                    "다른 경기가 이미 사용 중인 맵입니다."
            );
        }
    }

    public void release(String id, UUID matchId) {
        requireMainThread();

        if (id != null && matchId != null) {
            reservations.remove(id.toLowerCase(Locale.ROOT), matchId);
        }
    }

    public void clearReservations() {
        requireMainThread();
        reservations.clear();
    }

    public void save() {
        requireMainThread();

        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("maps");

        for (ArenaMap map : all()) {
            ConfigurationSection section = root.createSection(map.id());

            section.set("display-name", map.displayName());
            section.set("world", map.worldName());
            section.set("enabled", map.enabled());

            Locations.write(section, "bounds.pos1", map.pos1());
            Locations.write(section, "bounds.pos2", map.pos2());
            Locations.write(section, "spectator", map.spectator());

            Locations.write(section, "sites.a.pos1", map.siteA1());
            Locations.write(section, "sites.a.pos2", map.siteA2());
            Locations.write(section, "sites.b.pos1", map.siteB1());
            Locations.write(section, "sites.b.pos2", map.siteB2());

            writeSpawns(section, "spawns.attack", map.attackSpawns());
            writeSpawns(section, "spawns.defense", map.defenseSpawns());
        }

        try {
            yaml.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "maps.yml 저장에 실패했습니다.", exception
            );
        }
    }

    public List<String> ids() {
        return new ArrayList<>(all().stream().map(ArenaMap::id).toList());
    }

    private void loadSpawns(
            ConfigurationSection section,
            String path,
            ArenaMap map,
            boolean attacking
    ) {
        ConfigurationSection spawnSection =
                section.getConfigurationSection(path);

        if (spawnSection == null) {
            return;
        }

        List<String> keys = new ArrayList<>(spawnSection.getKeys(false));
        keys.sort(Comparator.comparingInt(key -> {
            try {
                return Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }));

        for (String key : keys) {
            Location location = Locations.read(spawnSection, key);

            if (location == null) {
                plugin.getLogger().warning(
                        "[맵 " + map.id() + "] " + path + "." + key
                                + " 좌표를 읽을 수 없습니다."
                );
                continue;
            }

            if (attacking) {
                map.addAttackSpawn(location);
            } else {
                map.addDefenseSpawn(location);
            }
        }
    }

    private void writeSpawns(
            ConfigurationSection section,
            String path,
            List<Location> spawns
    ) {
        section.set(path, null);
        ConfigurationSection child = section.createSection(path);

        for (int i = 0; i < spawns.size(); i++) {
            Locations.write(child, Integer.toString(i), spawns.get(i));
        }
    }

    private static String normalizeId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("맵 ID가 필요합니다.");
        }

        String id = value.toLowerCase(Locale.ROOT);

        if (!id.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException(
                    "맵 ID는 영문 소문자, 숫자, _, -로 1~32자여야 합니다."
            );
        }

        return id;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "맵 저장과 월드 검증은 메인 스레드에서 실행해야 합니다."
            );
        }
    }
}