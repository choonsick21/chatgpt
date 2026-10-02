package val.code.data;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class PlayerStateStore {

    private static final int FORMAT_VERSION = 1;

    private final JavaPlugin plugin;
    private final File directory;

    private final Map<UUID, Scoreboard> originalScoreboards = new HashMap<>();

    public PlayerStateStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(
                plugin.getDataFolder(), "player-states"
        );

        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException(
                    "플레이어 상태 저장 폴더를 만들 수 없습니다."
            );
        }
    }

    public boolean hasSnapshot(UUID playerId) {
        return file(playerId).isFile();
    }

    /**
     * 중복 capture는 원래 스냅샷을 덮어쓰지 않습니다.
     * 미복원 상태로 새 경기에 참가하려는 경우 시작을 거부합니다.
     */
    public void capture(Player player) {
        requireMainThread();

        UUID id = player.getUniqueId();

        if (hasSnapshot(id)) {
            throw new IllegalStateException(
                    player.getName()
                            + "님의 이전 상태가 아직 복원되지 않았습니다."
            );
        }

        if (player.isDead() || player.getHealth() <= 0) {
            throw new IllegalArgumentException(
                    "사망 상태에서는 경기에 참가할 수 없습니다."
            );
        }

        if (player.getGameMode() == GameMode.SPECTATOR
                && player.getSpectatorTarget() != null) {
            throw new IllegalArgumentException(
                    "기존 관전 대상을 해제한 뒤 참가하세요."
            );
        }

        YamlConfiguration yaml = new YamlConfiguration();

        yaml.set("format-version", FORMAT_VERSION);
        yaml.set("player-id", id.toString());
        yaml.set("player-name", player.getName());

        Locations.write(yaml, "location", player.getLocation());

        ItemStack[] contents = player.getInventory().getContents();
        List<ItemStack> inventory = new ArrayList<>(contents.length);

        for (ItemStack item : contents) {
            inventory.add(item == null ? null : item.clone());
        }

        yaml.set("inventory", inventory);

        ItemStack cursor = player.getItemOnCursor();
        yaml.set(
                "cursor",
                cursor == null || cursor.getType().isAir()
                        ? null : cursor.clone()
        );

        yaml.set(
                "held-slot",
                player.getInventory().getHeldItemSlot()
        );

        yaml.set("game-mode", player.getGameMode().name());
        yaml.set("health", player.getHealth());
        yaml.set("absorption", player.getAbsorptionAmount());

        yaml.set("food-level", player.getFoodLevel());
        yaml.set("saturation", player.getSaturation());
        yaml.set("exhaustion", player.getExhaustion());

        yaml.set("experience-progress", player.getExp());
        yaml.set("experience-level", player.getLevel());
        yaml.set("experience-total", player.getTotalExperience());

        yaml.set("allow-flight", player.getAllowFlight());
        yaml.set("flying", player.isFlying());
        yaml.set("fly-speed", player.getFlySpeed());
        yaml.set("walk-speed", player.getWalkSpeed());

        yaml.set("fall-distance", player.getFallDistance());
        yaml.set("fire-ticks", player.getFireTicks());
        yaml.set("freeze-ticks", player.getFreezeTicks());
        yaml.set("remaining-air", player.getRemainingAir());
        yaml.set("no-damage-ticks", player.getNoDamageTicks());

        yaml.set("invulnerable", player.isInvulnerable());
        yaml.set("collidable", player.isCollidable());
        yaml.set("gravity", player.hasGravity());

        Vector velocity = player.getVelocity();
        yaml.set("velocity.x", velocity.getX());
        yaml.set("velocity.y", velocity.getY());
        yaml.set("velocity.z", velocity.getZ());

        yaml.set("sprinting", player.isSprinting());
        yaml.set("sneaking", player.isSneaking());

        ConfigurationSection effects = yaml.createSection("effects");
        int index = 0;

        for (PotionEffect effect : player.getActivePotionEffects()) {
            ConfigurationSection section = effects.createSection(
                    Integer.toString(index++)
            );

            section.set(
                    "type",
                    effect.getType().getKey().toString()
            );
            section.set("duration", effect.getDuration());
            section.set("amplifier", effect.getAmplifier());
            section.set("ambient", effect.isAmbient());
            section.set("particles", effect.hasParticles());
            section.set("icon", effect.hasIcon());
        }

        saveAtomically(file(id), yaml);

        // ディスク保存成功後に、実行中サーバーの元のボードを保持します。
        originalScoreboards.put(id, player.getScoreboard());
    }

    /**
     * @param returnToOriginalLocation trueなら元の場所へ移動。
     *                                falseなら指定された安全位置へ移動。
     * @param safeLocation 元の場所が無効な場合の代替位置。
     * @return スナップショットが存在し、復元を完了した場合true。
     */
    public boolean restore(
            Player player,
            boolean returnToOriginalLocation,
            Location safeLocation
    ) {
        requireMainThread();

        File snapshotFile = file(player.getUniqueId());

        if (!snapshotFile.isFile()) {
            return false;
        }

        if (player.isDead()) {
            plugin.getLogger().warning(
                    player.getName()
                            + "님은 사망 상태이므로 리스폰 후 복원해야 합니다."
            );
            return false;
        }

        YamlConfiguration yaml = loadStrict(snapshotFile);

        validateIdentity(player.getUniqueId(), yaml);

        GameMode originalMode;

        try {
            originalMode = GameMode.valueOf(
                    yaml.getString("game-mode", "SURVIVAL")
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "저장된 게임모드가 잘못되었습니다: "
                            + snapshotFile.getName(),
                    exception
            );
        }

        ItemStack[] inventory = readInventory(yaml);
        ItemStack cursor = yaml.getItemStack("cursor");
        List<PotionEffect> effects = readEffects(yaml);

        Location originalLocation = Locations.read(yaml, "location");
        Location destination = returnToOriginalLocation
                ? originalLocation : copy(safeLocation);

        if (destination == null) {
            destination = copy(safeLocation);
        }

        if (destination == null) {
            destination = player.getWorld().getSpawnLocation();
        }

        if (returnToOriginalLocation && originalLocation == null) {
            plugin.getLogger().warning(
                    player.getName()
                            + "님의 원래 월드를 찾을 수 없어 안전 위치로 복원합니다."
            );
        }

        player.closeInventory();

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }

        player.setGameMode(originalMode);

        // 경기 효과를 먼저 제거한 후 원래 효과를 다시 적용합니다.
        for (PotionEffect active :
                new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(active.getType());
        }

        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }

        player.getInventory().setContents(inventory);
        player.setItemOnCursor(
                cursor == null ? null : cursor.clone()
        );

        player.getInventory().setHeldItemSlot(
                integer(yaml, "held-slot", 0, 0, 8)
        );

        player.setFoodLevel(
                integer(yaml, "food-level", 20, 0, 20)
        );

        float saturation = (float) decimal(
                yaml,
                "saturation",
                5,
                0,
                player.getFoodLevel()
        );

        player.setSaturation(saturation);
        player.setExhaustion(
                (float) decimal(yaml, "exhaustion", 0, 0, 40)
        );

        player.setTotalExperience(
                integer(
                        yaml,
                        "experience-total",
                        0,
                        0,
                        Integer.MAX_VALUE
                )
        );
        player.setLevel(
                integer(yaml, "experience-level", 0, 0, 1_000_000)
        );
        player.setExp(
                (float) decimal(
                        yaml, "experience-progress", 0, 0, 0.999999
                )
        );

        player.setWalkSpeed(
                (float) decimal(yaml, "walk-speed", 0.2, -1, 1)
        );
        player.setFlySpeed(
                (float) decimal(yaml, "fly-speed", 0.1, -1, 1)
        );

        boolean allowFlight = yaml.getBoolean(
                "allow-flight",
                originalMode == GameMode.CREATIVE
                        || originalMode == GameMode.SPECTATOR
        );

        player.setAllowFlight(allowFlight);
        player.setFlying(
                allowFlight && yaml.getBoolean("flying", false)
        );

        player.setInvulnerable(
                yaml.getBoolean("invulnerable", false)
        );
        player.setCollidable(
                yaml.getBoolean("collidable", true)
        );
        player.setGravity(
                yaml.getBoolean("gravity", true)
        );

        AttributeInstance maxHealth = player.getAttribute(
                Attribute.MAX_HEALTH
        );

        double healthLimit = maxHealth == null
                ? 20.0 : maxHealth.getValue();

        player.setHealth(
                decimal(
                        yaml,
                        "health",
                        healthLimit,
                        Math.min(0.01, healthLimit),
                        healthLimit
                )
        );

        player.setAbsorptionAmount(
                decimal(yaml, "absorption", 0, 0, 2048)
        );

        player.setFireTicks(
                integer(yaml, "fire-ticks", 0, -20, 72_000)
        );
        player.setFreezeTicks(
                integer(yaml, "freeze-ticks", 0, 0, 72_000)
        );
        player.setRemainingAir(
                integer(
                        yaml,
                        "remaining-air",
                        player.getMaximumAir(),
                        -20,
                        player.getMaximumAir()
                )
        );
        player.setNoDamageTicks(
                integer(yaml, "no-damage-ticks", 0, 0, 1200)
        );

        Scoreboard scoreboard = originalScoreboards.get(
                player.getUniqueId()
        );

        if (scoreboard != null) {
            player.setScoreboard(scoreboard);
        } else if (Bukkit.getScoreboardManager() != null) {
            player.setScoreboard(
                    Bukkit.getScoreboardManager().getMainScoreboard()
            );
        }

        if (!player.teleport(destination)) {
            // スナップショットは削除せず、後から復元を再試行できます。
            throw new IllegalStateException(
                    player.getName()
                            + "님의 복원 이동이 다른 플러그인에 의해 취소되었습니다."
            );
        }

        boolean restoredOriginalPosition = returnToOriginalLocation
                && originalLocation != null;

        if (restoredOriginalPosition) {
            player.setFallDistance(
                    (float) decimal(
                            yaml, "fall-distance", 0, 0, 100_000
                    )
            );

            player.setVelocity(new Vector(
                    decimal(yaml, "velocity.x", 0, -10, 10),
                    decimal(yaml, "velocity.y", 0, -10, 10),
                    decimal(yaml, "velocity.z", 0, -10, 10)
            ));
        } else {
            player.setFallDistance(0);
            player.setVelocity(new Vector());
        }

        player.setSprinting(
                yaml.getBoolean("sprinting", false)
        );
        player.setSneaking(
                yaml.getBoolean("sneaking", false)
        );

        player.updateInventory();

        try {
            Files.delete(snapshotFile.toPath());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "복원은 수행했으나 상태 파일 삭제에 실패했습니다: "
                            + snapshotFile.getName(),
                    exception
            );
        }

        originalScoreboards.remove(player.getUniqueId());
        return true;
    }

    public boolean restore(
            Player player,
            Location safeLocation
    ) {
        return restore(player, true, safeLocation);
    }

    /**
     * 비활성화 중에도 한 플레이어의 복원 오류가
     * 다른 플레이어 복원을 중단시키지 않습니다.
     */
    public void restoreAllOnline(Location safeLocation) {
        requireMainThread();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!hasSnapshot(player.getUniqueId())) {
                continue;
            }

            try {
                restore(player, true, safeLocation);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        player.getName()
                                + "님 상태 복원 실패. 파일을 유지합니다.",
                        exception
                );
            }
        }
    }

    /**
     * 남아 있는 스냅샷 수를 관리자 로그에 표시할 때 사용합니다.
     * 진행 중 경기를 복원하는 용도가 아닙니다.
     */
    public int pendingCount() {
        File[] files = directory.listFiles(
                (dir, name) -> name.endsWith(".yml")
        );

        return files == null ? 0 : files.length;
    }

    /**
     * 접속 종료 후 보드 객체 참조를 해제합니다.
     * 상태 파일 자체는 삭제하지 않습니다.
     */
    public void forgetRuntimeReferences(UUID playerId) {
        requireMainThread();
        originalScoreboards.remove(playerId);
    }

    private ItemStack[] readInventory(YamlConfiguration yaml) {
        List<?> contents = yaml.getList("inventory");

        // Paper 1.21.8 플레이어 인벤토리:
        // 일반 36칸 + 방어구 4칸 + 보조 손 1칸.
        if (contents == null || contents.size() != 41) {
            throw new IllegalStateException(
                    "저장된 플레이어 인벤토리는 41칸이어야 합니다."
            );
        }

        ItemStack[] result = new ItemStack[41];

        for (int index = 0; index < contents.size(); index++) {
            Object value = contents.get(index);

            if (value == null) {
                result[index] = null;
            } else if (value instanceof ItemStack item) {
                result[index] = item.clone();
            } else {
                throw new IllegalStateException(
                        "인벤토리 #" + index
                                + "에 잘못된 아이템 데이터가 있습니다."
                );
            }
        }

        return result;
    }

    private List<PotionEffect> readEffects(
            YamlConfiguration yaml
    ) {
        List<PotionEffect> result = new ArrayList<>();
        ConfigurationSection root =
                yaml.getConfigurationSection("effects");

        if (root == null) {
            return result;
        }

        for (String index : root.getKeys(false)) {
            ConfigurationSection section =
                    root.getConfigurationSection(index);

            if (section == null) {
                throw new IllegalStateException(
                        "저장된 상태 효과 데이터가 잘못되었습니다."
                );
            }

            String typeName = section.getString("type");

            if (typeName == null) {
                throw new IllegalStateException(
                        "저장된 상태 효과의 종류가 없습니다."
                );
            }

            NamespacedKey key = NamespacedKey.fromString(typeName);

            PotionEffectType type = key == null
                    ? null : PotionEffectType.getByKey(key);

            if (type == null) {
                plugin.getLogger().warning(
                        "복원할 수 없는 상태 효과를 건너뜁니다: "
                                + typeName
                );
                continue;
            }

            int duration = section.getInt("duration", 0);

            if (duration < -1) {
                throw new IllegalStateException(
                        "저장된 상태 효과 지속 시간이 잘못되었습니다."
                );
            }

            result.add(new PotionEffect(
                    type,
                    duration,
                    integer(section, "amplifier", 0, 0, 255),
                    section.getBoolean("ambient", false),
                    section.getBoolean("particles", true),
                    section.getBoolean("icon", true)
            ));
        }

        return result;
    }

    private YamlConfiguration loadStrict(File snapshotFile) {
        YamlConfiguration yaml = new YamlConfiguration();

        try {
            yaml.load(snapshotFile);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(
                    "플레이어 상태 파일을 읽을 수 없습니다: "
                            + snapshotFile.getName(),
                    exception
            );
        }

        return yaml;
    }

    private void validateIdentity(
            UUID expectedId,
            YamlConfiguration yaml
    ) {
        if (yaml.getInt("format-version", -1) != FORMAT_VERSION) {
            throw new IllegalStateException(
                    "지원하지 않는 플레이어 상태 파일 버전입니다."
            );
        }

        String rawId = yaml.getString("player-id");

        if (!expectedId.toString().equals(rawId)) {
            throw new IllegalStateException(
                    "상태 파일의 플레이어 UUID가 일치하지 않습니다."
            );
        }
    }

    private void saveAtomically(
            File target,
            YamlConfiguration yaml
    ) {
        File temporary = new File(
                directory,
                target.getName() + ".tmp"
        );

        try {
            yaml.save(temporary);

            try {
                Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "플레이어 상태 저장에 실패했습니다. "
                            + "원래 상태를 보호하기 위해 경기 시작을 중단합니다.",
                    exception
            );
        } finally {
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException exception) {
                plugin.getLogger().warning(
                        "임시 상태 파일을 삭제하지 못했습니다: "
                                + temporary.getName()
                );
            }
        }
    }

    private File file(UUID playerId) {
        return new File(directory, playerId + ".yml");
    }

    private static int integer(
            ConfigurationSection section,
            String path,
            int fallback,
            int minimum,
            int maximum
    ) {
        int value = section.getInt(path, fallback);
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double decimal(
            ConfigurationSection section,
            String path,
            double fallback,
            double minimum,
            double maximum
    ) {
        double value = section.getDouble(path, fallback);

        if (!Double.isFinite(value)) {
            value = fallback;
        }

        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Location copy(Location location) {
        return location == null ? null : location.clone();
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "플레이어 상태 저장과 복원은 메인 스레드에서 실행해야 합니다."
            );
        }
    }
}