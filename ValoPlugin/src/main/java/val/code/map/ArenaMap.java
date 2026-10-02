package val.code.map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import val.code.data.Locations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ArenaMap {

    private final String id;

    private String displayName;
    private String worldName;
    private boolean enabled;

    private Location pos1;
    private Location pos2;
    private Location spectator;

    private Location siteA1;
    private Location siteA2;
    private Location siteB1;
    private Location siteB2;

    private final List<Location> attackSpawns = new ArrayList<>();
    private final List<Location> defenseSpawns = new ArrayList<>();

    public ArenaMap(String id) {
        this.id = id;
        this.displayName = id;
        this.enabled = false;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public void displayName(String value) {
        if (value == null || value.isBlank() || value.length() > 48) {
            throw new IllegalArgumentException("맵 표시 이름은 1~48자여야 합니다.");
        }
        this.displayName = value;
    }

    public String worldName() {
        return worldName;
    }

    public World world() {
        return worldName == null ? null : Bukkit.getWorld(worldName);
    }

    public void worldName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("월드 이름이 비어 있습니다.");
        }
        this.worldName = value;
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean value) {
        this.enabled = value;
    }

    public Location pos1() {
        return copy(pos1);
    }

    public void pos1(Location value) {
        pos1 = copy(value);
    }

    public Location pos2() {
        return copy(pos2);
    }

    public void pos2(Location value) {
        pos2 = copy(value);
    }

    public Location spectator() {
        return copy(spectator);
    }

    public void spectator(Location value) {
        spectator = copy(value);
    }

    public Location siteA1() {
        return copy(siteA1);
    }

    public void siteA1(Location value) {
        siteA1 = copy(value);
    }

    public Location siteA2() {
        return copy(siteA2);
    }

    public void siteA2(Location value) {
        siteA2 = copy(value);
    }

    public Location siteB1() {
        return copy(siteB1);
    }

    public void siteB1(Location value) {
        siteB1 = copy(value);
    }

    public Location siteB2() {
        return copy(siteB2);
    }

    public void siteB2(Location value) {
        siteB2 = copy(value);
    }

    public List<Location> attackSpawns() {
        return immutableCopies(attackSpawns);
    }

    public List<Location> defenseSpawns() {
        return immutableCopies(defenseSpawns);
    }

    public void addAttackSpawn(Location location) {
        requireLocation(location);
        attackSpawns.add(location.clone());
    }

    public void addDefenseSpawn(Location location) {
        requireLocation(location);
        defenseSpawns.add(location.clone());
    }

    public void clearAttackSpawns() {
        attackSpawns.clear();
    }

    public void clearDefenseSpawns() {
        defenseSpawns.clear();
    }

    public Location spawn(boolean attacking, int index) {
        List<Location> source = attacking ? attackSpawns : defenseSpawns;

        if (source.isEmpty()) {
            return spectator();
        }

        return source.get(Math.floorMod(index, source.size())).clone();
    }

    public boolean contains(Location location) {
        return inside(location, pos1, pos2);
    }

    public boolean siteAContains(Location location) {
        return inside(location, siteA1, siteA2);
    }

    public boolean siteBContains(Location location) {
        return inside(location, siteB1, siteB2);
    }

    public boolean isPlantSite(Location location) {
        return contains(location)
                && (siteAContains(location) || siteBContains(location));
    }

    /**
     * 플레이어 몸체까지 맵 경계 안에 들어오는지 검사합니다.
     * 관전자도 같은 경계 규칙을 사용합니다.
     */
    public boolean containsPlayer(Location feet) {
        if (!contains(feet)) {
            return false;
        }

        double padding = 0.30;
        double height = 1.80;

        for (double dx : new double[]{-padding, padding}) {
            for (double dz : new double[]{-padding, padding}) {
                if (!contains(feet.clone().add(dx, 0, dz))
                        || !contains(feet.clone().add(dx, height, dz))) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 목록 표시에서는 활성화 여부와 무관한 구조 검증도 가능합니다.
     */
    public List<String> problems(boolean requireEnabled) {
        List<String> result = new ArrayList<>();

        if (requireEnabled && !enabled) {
            result.add("맵이 비활성화되어 있습니다.");
        }

        World world = world();

        if (worldName == null || worldName.isBlank()) {
            result.add("맵 월드가 지정되지 않았습니다.");
        } else if (world == null) {
            result.add("월드 '" + worldName + "'가 로드되지 않았습니다.");
        }

        checkPoint(result, "경계 pos1", pos1, world, false);
        checkPoint(result, "경계 pos2", pos2, world, false);

        if (pos1 != null && pos2 != null
                && Locations.sameWorld(pos1, pos2)) {
            double width = Math.abs(pos1.getBlockX() - pos2.getBlockX()) + 1;
            double height = Math.abs(pos1.getBlockY() - pos2.getBlockY()) + 1;
            double depth = Math.abs(pos1.getBlockZ() - pos2.getBlockZ()) + 1;

            if (width < 4 || height < 4 || depth < 4) {
                result.add("맵 경계는 각 축에 최소 4블록의 공간이 필요합니다.");
            }
        }

        checkPoint(result, "관전자 위치", spectator, world, true);

        if (attackSpawns.isEmpty()) {
            result.add("공격팀 스폰이 없습니다.");
        }

        if (defenseSpawns.isEmpty()) {
            result.add("수비팀 스폰이 없습니다.");
        }

        for (int i = 0; i < attackSpawns.size(); i++) {
            checkPoint(result, "공격팀 스폰 #" + (i + 1),
                    attackSpawns.get(i), world, true);
        }

        for (int i = 0; i < defenseSpawns.size(); i++) {
            checkPoint(result, "수비팀 스폰 #" + (i + 1),
                    defenseSpawns.get(i), world, true);
        }

        checkPoint(result, "설치 구역 A pos1", siteA1, world, false);
        checkPoint(result, "설치 구역 A pos2", siteA2, world, false);
        checkPoint(result, "설치 구역 B pos1", siteB1, world, false);
        checkPoint(result, "설치 구역 B pos2", siteB2, world, false);

        for (Location siteCorner :
                new Location[]{siteA1, siteA2, siteB1, siteB2}) {
            if (siteCorner != null && !contains(siteCorner)) {
                result.add("설치 구역의 모든 모서리는 맵 경계 안에 있어야 합니다.");
                break;
            }
        }

        return Collections.unmodifiableList(result);
    }

    private void checkPoint(
            List<String> problems,
            String label,
            Location location,
            World expectedWorld,
            boolean playerPosition
    ) {
        if (location == null) {
            problems.add(label + "이 지정되지 않았거나 월드가 로드되지 않았습니다.");
            return;
        }

        if (expectedWorld == null
                || location.getWorld() == null
                || !expectedWorld.getUID()
                .equals(location.getWorld().getUID())) {
            problems.add(label + "의 월드가 맵 월드와 다릅니다.");
            return;
        }

        if (location.getY() < expectedWorld.getMinHeight()
                || location.getY() >= expectedWorld.getMaxHeight()) {
            problems.add(label + "이 월드 높이 범위 밖에 있습니다.");
        }

        if (playerPosition && !containsPlayer(location)) {
            problems.add(label + "에 플레이어 몸체가 경계 밖으로 나갑니다.");
        }

        if (playerPosition
                && (!location.getBlock().isPassable()
                || !location.clone().add(0, 1, 0).getBlock().isPassable())) {
            problems.add(label + "의 발 또는 머리 위치가 블록으로 막혀 있습니다.");
        }
    }

    private static boolean inside(
            Location location,
            Location first,
            Location second
    ) {
        if (!Locations.sameWorld(location, first)
                || !Locations.sameWorld(location, second)) {
            return false;
        }

        double minX = Math.min(first.getBlockX(), second.getBlockX());
        double minY = Math.min(first.getBlockY(), second.getBlockY());
        double minZ = Math.min(first.getBlockZ(), second.getBlockZ());

        double maxX = Math.max(first.getBlockX(), second.getBlockX()) + 1.0;
        double maxY = Math.max(first.getBlockY(), second.getBlockY()) + 1.0;
        double maxZ = Math.max(first.getBlockZ(), second.getBlockZ()) + 1.0;

        return location.getX() >= minX && location.getX() < maxX
                && location.getY() >= minY && location.getY() < maxY
                && location.getZ() >= minZ && location.getZ() < maxZ;
    }

    private static Location copy(Location location) {
        return location == null ? null : location.clone();
    }

    private static List<Location> immutableCopies(List<Location> source) {
        return source.stream().map(Location::clone).toList();
    }

    private static void requireLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("유효한 월드 좌표가 필요합니다.");
        }
    }
}