package val.code.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import val.code.config.Settings;
import val.code.data.Locations;
import val.code.sound.SoundService;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LobbyService implements Listener {

    private final JavaPlugin plugin;
    private final Settings settings;
    private final SoundService sounds;

    private final File file;
    private final NamespacedKey entityKey;

    private final List<UUID> activeEntities = new ArrayList<>();

    private YamlConfiguration yaml;
    private boolean closing;

    public LobbyService(
            JavaPlugin plugin,
            Settings settings,
            SoundService sounds
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.sounds = sounds;
        this.file = new File(plugin.getDataFolder(), "lobby.yml");
        this.entityKey = new NamespacedKey(plugin, "lobby-entity");
    }

    public void load() {
        requireMainThread();
        closing = false;

        if (!file.exists()) {
            plugin.saveResource("lobby.yml", false);
        }

        yaml = YamlConfiguration.loadConfiguration(file);

        removeLoadedEntities();
        rebuildButton();

        if (yaml.isConfigurationSection("lobby")
                && yaml.contains("lobby.world")
                && location() == null) {
            plugin.getLogger().warning(
                    "로비 위치의 월드가 로드되지 않았거나 좌표가 잘못되었습니다."
            );
        }
    }

    public Location location() {
        return yaml == null ? null : Locations.read(yaml, "lobby");
    }

    public Location buttonLocation() {
        return yaml == null ? null : Locations.read(yaml, "button");
    }

    public boolean hasLobby() {
        return location() != null;
    }

    public boolean isLobbyWorld(World world) {
        Location location = location();

        return world != null
                && location != null
                && location.getWorld() != null
                && world.getUID().equals(location.getWorld().getUID());
    }

    public void setLobby(Location location) {
        requireMainThread();
        requireLocation(location);

        Locations.write(yaml, "lobby", location);
        save();
    }

    public void installButton(Location location) {
        requireMainThread();
        requireLocation(location);

        removeLoadedEntities();
        Locations.write(yaml, "button", location);
        save();
        rebuildButton();
    }

    public void deleteButton() {
        requireMainThread();

        removeLoadedEntities();
        yaml.set("button", null);
        save();
    }

    public void reinstallButton() {
        requireMainThread();

        if (buttonLocation() == null) {
            throw new IllegalArgumentException(
                    "저장된 PLAY 버튼 위치가 없습니다."
            );
        }

        removeLoadedEntities();
        rebuildButton();
    }

    public boolean isPlayButton(Entity entity) {
        return entity instanceof Interaction
                && "play".equals(
                entity.getPersistentDataContainer().get(
                        entityKey, PersistentDataType.STRING
                )
        );
    }

    public boolean isLobbyEntity(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer()
                .has(entityKey, PersistentDataType.STRING);
    }

    /**
     * 인벤토리·체력 등의 경기 상태 복원은 PlayerStateStore에서 수행합니다.
     * 이 메서드는 로비 이동과 연출만 담당합니다.
     */
    public boolean sendToLobby(Player player) {
        requireMainThread();

        Location lobby = location();

        if (lobby == null) {
            player.sendMessage(
                    Component.text(
                            "관리자가 아직 메인 로비를 설정하지 않았습니다.",
                            NamedTextColor.RED
                    )
            );
            return false;
        }

        if (!player.teleport(lobby)) {
            player.sendMessage(
                    Component.text(
                            "다른 플러그인이 로비 이동을 막았습니다.",
                            NamedTextColor.RED
                    )
            );
            return false;
        }

        player.setFallDistance(0);
        player.setVelocity(new org.bukkit.util.Vector());

        player.showTitle(Title.title(
                Component.text(
                        settings.text("lobby.title", "전술 훈련장"),
                        NamedTextColor.WHITE
                ),
                Component.text(
                        settings.text(
                                "lobby.subtitle",
                                "PLAY 버튼을 눌러 경기에 참가하세요"
                        ),
                        NamedTextColor.GRAY
                ),
                Title.Times.times(
                        Duration.ofMillis(250),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(600)
                )
        ));

        sounds.play(player, "lobby-enter");
        sounds.play(player, "lobby-music");
        return true;
    }

    public void shutdown() {
        requireMainThread();
        closing = true;
        removeLoadedEntities();

        for (Player player : Bukkit.getOnlinePlayers()) {
            sounds.stop(player, "lobby-music");
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (closing || yaml == null) {
            return;
        }

        // 과거 서버 종료가 비정상적이어서 저장된 엔티티가 남은 경우 정리.
        for (Entity entity : event.getChunk().getEntities()) {
            if (isLobbyEntity(entity)
                    && !activeEntities.contains(entity.getUniqueId())) {
                entity.remove();
            }
        }

        Location button = buttonLocation();

        if (button == null
                || !button.getWorld().getUID()
                .equals(event.getWorld().getUID())) {
            return;
        }

        int targetX = button.getBlockX() >> 4;
        int targetZ = button.getBlockZ() >> 4;

        if (event.getChunk().getX() == targetX
                && event.getChunk().getZ() == targetZ) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!closing) {
                    ensureButton();
                }
            });
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (!closing && yaml != null) {
            Bukkit.getScheduler().runTask(plugin, this::ensureButton);
        }
    }

    private void ensureButton() {
        if (closing) {
            return;
        }

        Location location = buttonLocation();

        if (location == null) {
            return;
        }

        long existing = activeEntities.stream()
                .map(Bukkit::getEntity)
                .filter(entity -> entity != null && entity.isValid())
                .count();

        if (existing == 2) {
            return;
        }

        removeLoadedEntities();
        rebuildButton();
    }

    private void rebuildButton() {
        if (closing) {
            return;
        }

        Location location = buttonLocation();

        if (location == null) {
            return;
        }

        World world = location.getWorld();
        world.getChunkAt(location).load();

        Interaction interaction = world.spawn(
                location,
                Interaction.class,
                entity -> {
                    entity.setInteractionWidth(
                            (float) settings.decimal(
                                    "lobby.button-width", 1.6, 0.5, 4.0
                            )
                    );
                    entity.setInteractionHeight(
                            (float) settings.decimal(
                                    "lobby.button-height", 1.6, 0.5, 4.0
                            )
                    );
                    entity.setResponsive(true);
                    entity.setInvulnerable(true);

                    // 設定位置から再生成するため、ワールドに永続保存しません。
                    entity.setPersistent(false);

                    entity.getPersistentDataContainer().set(
                            entityKey,
                            PersistentDataType.STRING,
                            "play"
                    );
                }
        );

        TextDisplay display = world.spawn(
                location.clone().add(0, 0.75, 0),
                TextDisplay.class,
                entity -> {
                    entity.text(
                            Component.text("PLAY", NamedTextColor.RED)
                                    .decorate(TextDecoration.BOLD)
                                    .append(Component.newline())
                                    .append(Component.text(
                                            "우클릭하여 경기 찾기",
                                            NamedTextColor.WHITE
                                    ))
                    );

                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                    entity.setShadowed(true);
                    entity.setSeeThrough(false);
                    entity.setDefaultBackground(true);
                    entity.setLineWidth(240);
                    entity.setViewRange(0.7F);
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);

                    entity.getPersistentDataContainer().set(
                            entityKey,
                            PersistentDataType.STRING,
                            "play-text"
                    );
                }
        );

        activeEntities.add(interaction.getUniqueId());
        activeEntities.add(display.getUniqueId());
    }

    private void removeLoadedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isLobbyEntity(entity)) {
                    entity.remove();
                }
            }
        }

        activeEntities.clear();
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "lobby.yml 저장에 실패했습니다.", exception
            );
        }
    }

    private static void requireLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException(
                    "로드된 월드의 위치가 필요합니다."
            );
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "로비 엔티티 작업은 메인 스레드에서 실행해야 합니다."
            );
        }
    }
}