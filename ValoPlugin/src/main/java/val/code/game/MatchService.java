package val.code.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import val.code.config.Settings;
import val.code.data.PlayerStateStore;
import val.code.item.GameItems;
import val.code.lobby.LobbyService;
import val.code.map.ArenaMap;
import val.code.map.MapRepository;
import val.code.party.Room;
import val.code.party.RoomService;
import val.code.sound.SoundService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;

public final class MatchService {

    public enum Screen {
        SELECT,
        SHOP,
        SPECTATE,
        RESULT
    }

    private final JavaPlugin plugin;
    private final Settings settings;
    private final MapRepository maps;
    private final RoomService rooms;
    private final GameItems items;
    private final SoundService sounds;
    private final LobbyService lobby;
    private final PlayerStateStore states;

    private final Map<UUID, Match> matches = new LinkedHashMap<>();
    private final Map<UUID, UUID> byPlayer = new HashMap<>();

    private BiConsumer<Player, Screen> screenHandler;
    private BukkitTask ticker;
    private boolean closing;

    public MatchService(
            JavaPlugin plugin,
            Settings settings,
            MapRepository maps,
            RoomService rooms,
            GameItems items,
            SoundService sounds,
            LobbyService lobby,
            PlayerStateStore states
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.maps = maps;
        this.rooms = rooms;
        this.items = items;
        this.sounds = sounds;
        this.lobby = lobby;
        this.states = states;

        rooms.setDepartureHandler(this::onRoomDeparture);
    }

    public Settings settings() {
        return settings;
    }

    public GameItems items() {
        return items;
    }

    public SoundService sounds() {
        return sounds;
    }

    public PlayerStateStore states() {
        return states;
    }

    public Collection<Match> all() {
        return List.copyOf(matches.values());
    }

    public Match find(UUID matchId) {
        return matches.get(matchId);
    }

    public Match of(UUID playerId) {
        UUID matchId = byPlayer.get(playerId);
        return matchId == null ? null : matches.get(matchId);
    }

    public Match of(Player player) {
        return of(player.getUniqueId());
    }

    public boolean inMatch(UUID playerId) {
        return of(playerId) != null;
    }

    public void setScreenHandler(
            BiConsumer<Player, Screen> handler
    ) {
        requireMainThread();

        if (handler == null) {
            throw new IllegalArgumentException(
                    "화면 처리기가 필요합니다."
            );
        }

        screenHandler = handler;
    }

    public void startTicker() {
        requireMainThread();

        if (ticker != null) {
            throw new IllegalStateException(
                    "경기 틱 작업이 이미 실행 중입니다."
            );
        }

        ticker = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tickAll,
                1,
                1
        );
    }

    public Match start(Player leader) {
        requireMainThread();
        requireOpen();

        if (screenHandler == null) {
            throw new IllegalStateException(
                    "경기 GUI가 연결되지 않았습니다."
            );
        }

        if (!lobby.hasLobby()) {
            throw new IllegalArgumentException(
                    "메인 로비를 먼저 설정해야 합니다."
            );
        }

        Room room = rooms.of(leader);

        if (room == null) {
            throw new IllegalArgumentException(
                    "참가 중인 방이 없습니다."
            );
        }

        if (!room.isLeader(leader.getUniqueId())) {
            throw new IllegalArgumentException(
                    "방장만 경기를 시작할 수 있습니다."
            );
        }

        if (room.state() != Room.State.WAITING) {
            throw new IllegalArgumentException(
                    "이미 시작한 방입니다."
            );
        }

        int maximumCapacity = settings.integer(
                "rooms.maximum-capacity", 10, 2, 20
        );
        int minimumPlayers = settings.integer(
                "rooms.minimum-players",
                2,
                2,
                maximumCapacity
        );

        if (room.size() < minimumPlayers) {
            throw new IllegalArgumentException(
                    "경기에 최소 " + minimumPlayers + "명이 필요합니다."
            );
        }

        if (room.mapId() == null) {
            throw new IllegalArgumentException(
                    "방장이 먼저 맵을 선택해야 합니다."
            );
        }

        ArenaMap map = maps.require(room.mapId());

        Location lobbyLocation = lobby.location();

        if (map.contains(lobbyLocation)) {
            throw new IllegalArgumentException(
                    "메인 로비가 경기 맵 경계 안에 있습니다. "
                            + "로비를 맵 경계 밖으로 설정하세요."
            );
        }

        List<String> problems = map.problems(true);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "맵 설정이 완료되지 않았습니다:\n- "
                            + String.join("\n- ", problems)
            );
        }

        for (UUID playerId : room.memberIds()) {
            Player player = Bukkit.getPlayer(playerId);

            if (player == null || !player.isOnline()) {
                throw new IllegalArgumentException(
                        room.memberName(playerId)
                                + "님이 접속 중이 아닙니다."
                );
            }

            if (player.isDead()) {
                throw new IllegalArgumentException(
                        player.getName() + "님이 사망 상태입니다."
                );
            }

            if (byPlayer.containsKey(playerId)) {
                throw new IllegalArgumentException(
                        player.getName()
                                + "님이 이미 다른 경기에 참가 중입니다."
                );
            }

            if (states.hasSnapshot(playerId)) {
                throw new IllegalArgumentException(
                        player.getName()
                                + "님의 이전 상태를 먼저 복원해야 합니다."
                );
            }
        }

        UUID matchId = UUID.randomUUID();

        // 메인 스레드에서 맵 예약을 먼저 획득합니다.
        maps.reserve(map.id(), matchId);

        Match match = null;

        try {
            match = new Match(this, matchId, room, map);

            rooms.beginMatch(room.id(), matchId);

            matches.put(matchId, match);

            for (UUID member : room.memberIds()) {
                byPlayer.put(member, matchId);
            }

            match.initialize();

            rooms.broadcast(
                    room,
                    "경기를 준비합니다. 진행 중 참가와 설정 변경은 제한됩니다.",
                    NamedTextColor.AQUA
            );

            return match;
        } catch (RuntimeException exception) {
            if (match != null) {
                try {
                    complete(match);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            } else {
                maps.release(map.id(), matchId);
                rooms.finishMatch(room.id(), matchId);
            }

            throw exception;
        }
    }

    public void openScreen(Player player, Screen screen) {
        requireMainThread();

        if (closing || !player.isOnline()) {
            return;
        }

        Match match = of(player);

        if (match == null || !match.isPlaying(player.getUniqueId())) {
            return;
        }

        if (screenHandler == null) {
            throw new IllegalStateException(
                    "경기 화면 처리기가 연결되지 않았습니다."
            );
        }

        screenHandler.accept(player, screen);
    }

    public void openAgent(Player player) {
        Match match = requireMatch(player);

        if (match.phase() != MatchPhase.SELECTING) {
            throw new IllegalArgumentException(
                    "현재 요원 선택 단계가 아닙니다."
            );
        }

        openScreen(player, Screen.SELECT);
    }

    public void openShop(Player player) {
        Match match = requireMatch(player);

        if (!match.canBuy(player)) {
            throw new IllegalArgumentException(
                    "구매 단계의 팀 스폰 근처에서만 상점을 열 수 있습니다."
            );
        }

        openScreen(player, Screen.SHOP);
    }

    public void openSpectate(Player player) {
        Match match = requireMatch(player);
        Fighter fighter = match.fighter(player.getUniqueId());

        if (fighter == null
                || fighter.alive()
                || !match.phase().isRoundActive()) {
            throw new IllegalArgumentException(
                    "라운드 중 사망한 참가자만 관전 대상을 선택할 수 있습니다."
            );
        }

        openScreen(player, Screen.SPECTATE);
    }

    public void returnToLobby(Player player) {
        requireMainThread();

        Match match = of(player);

        if (match == null) {
            if (states.hasSnapshot(player.getUniqueId())) {
                recover(player);
            } else {
                lobby.sendToLobby(player);
            }
            return;
        }

        if (match.phase() == MatchPhase.RESULT) {
            match.returnFromResult(player.getUniqueId());
            return;
        }

        // 진행 중 로비 복귀는 파티 퇴장과 동일합니다.
        if (rooms.of(player) != null) {
            rooms.leave(player.getUniqueId());
        } else {
            match.depart(player.getUniqueId());
        }
    }

    public void forceStop(UUID matchId, String reason) {
        requireMainThread();

        Match match = find(matchId);

        if (match == null) {
            throw new IllegalArgumentException(
                    "존재하지 않는 경기입니다."
            );
        }

        match.abort(reason);
    }

    public void forceStopAll(String reason) {
        requireMainThread();

        for (Match match : new ArrayList<>(matches.values())) {
            match.abort(reason);
        }
    }

    public void onQuit(Player player) {
        requireMainThread();

        if (rooms.of(player) != null) {
            rooms.leave(player.getUniqueId());
        } else {
            Match match = of(player);

            if (match != null) {
                match.depart(player.getUniqueId());
            }
        }

        states.forgetRuntimeReferences(player.getUniqueId());
    }

    /**
     * 재접속 시 진행 중 경기에 재참가시키지 않습니다.
     * 남은 원본 스냅샷이 있으면 복원합니다.
     */
    public boolean recover(Player player) {
        requireMainThread();

        if (!states.hasSnapshot(player.getUniqueId())) {
            removeOrphanedItems(player);
            return true;
        }

        if (player.isDead()) {
            return false;
        }

        try {
            boolean restored = states.restore(
                    player,
                    false,
                    lobby.location()
            );

            if (restored) {
                player.sendMessage(
                        Component.text(
                                "이전 경기의 원래 플레이어 상태를 복원했습니다.",
                                NamedTextColor.AQUA
                        )
                );
                lobby.sendToLobby(player);
            }

            return restored;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    player.getName()
                            + "님 원본 상태 복원 실패. "
                            + "player-states 파일을 삭제하지 마세요.",
                    exception
            );

            player.kick(
                    Component.text(
                            "원래 플레이어 상태 복원에 실패했습니다.\n"
                                    + "관리자에게 문의하세요.",
                            NamedTextColor.RED
                    )
            );

            return false;
        }
    }

    public boolean movementAllowed(
            Player player,
            Location destination
    ) {
        Match match = of(player);

        return match == null
                || match.movementAllowed(player, destination);
    }

    public Location safeLocation(Player player) {
        Match match = of(player);

        return match == null
                ? lobby.location()
                : match.safeLocation(player.getUniqueId());
    }

    /**
     * GUI·스킬 슬롯 이벤트에서 호출합니다.
     * 모든 특수 슬롯은 자동으로 무기 슬롯으로 복귀합니다.
     */
    public void handleSlot(Player player, int selectedSlot) {
        requireMainThread();

        Match match = of(player);

        if (match == null) {
            return;
        }

        Fighter fighter = match.fighter(player.getUniqueId());

        if (fighter == null || !fighter.connected()) {
            return;
        }

        if (selectedSlot >= 0 && selectedSlot <= 3) {
            fighter.lastWeaponSlot(selectedSlot);
            return;
        }

        if (selectedSlot != 4
                && selectedSlot != 5
                && selectedSlot != 6) {
            return;
        }

        if (!fighter.claimSlotAction(Bukkit.getCurrentTick())) {
            return;
        }

        // 슬롯 복귀는 이벤트 처리 종료 후 실행합니다.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Match current = of(player);

            if (!player.isOnline()
                    || current != match
                    || !fighter.connected()) {
                return;
            }

            player.getInventory().setHeldItemSlot(
                    fighter.lastWeaponSlot()
            );
        });

        try {
            if (selectedSlot == 4) {
                openShop(player);
                return;
            }

            if (!match.phase().allowsCombat() || !fighter.alive()) {
                throw new IllegalArgumentException(
                        "현재 상태에서는 제트 스킬을 사용할 수 없습니다."
                );
            }

            boolean used = selectedSlot == 5
                    ? match.jett().dash(player)
                    : match.jett().smoke(player);

            if (!used) {
                player.sendActionBar(
                        Component.text(
                                "충전·재사용 대기시간·사용 공간을 확인하세요.",
                                NamedTextColor.RED
                        )
                );
                sounds.play(player, "menu-error");
            }
        } catch (IllegalArgumentException exception) {
            player.sendActionBar(
                    Component.text(
                            exception.getMessage(),
                            NamedTextColor.RED
                    )
            );
            sounds.play(player, "menu-error");
        }
    }

    public void handleRightClick(Player player) {
        requireMainThread();

        Match match = of(player);

        if (match == null) {
            return;
        }

        Fighter fighter = match.fighter(player.getUniqueId());

        if (fighter == null || !fighter.connected()) {
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();

        if (!fighter.alive()) {
            if (items.is(held, GameItems.SPECTATE, match.id())
                    && match.phase().isRoundActive()) {
                openSpectate(player);
            }
            return;
        }

        if (items.is(held, GameItems.CLASSIC, match.id())) {
            match.classic().fire(player);
        } else if (match.phase().allowsCombat()) {
            match.spike().interact(player);
        }
    }

    public void handleReload(Player player) {
        requireMainThread();

        Match match = of(player);

        if (match != null) {
            match.classic().reload(player);
        }
    }

    /**
     * 경기 정리, 방 상태 복구, 예약 해제, 플레이어 복원을 수행합니다.
     */
    public void complete(Match match) {
        requireMainThread();

        if (matches.get(match.id()) != match) {
            return;
        }

        matches.remove(match.id());

        try {
            match.cleanup();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "경기 임시 상태 정리 실패: " + match.id(),
                    exception
            );
        } finally {
            maps.release(match.map().id(), match.id());
            rooms.finishMatch(match.roomId(), match.id());
        }

        for (Fighter fighter : match.fighters()) {
            releasePlayer(match, fighter.id(), !closing);
        }
    }

    /**
     * Match 내부에서 플레이어 하나를 종료할 때도 사용합니다.
     * 인덱스 제거를 먼저 수행하여 복원 텔레포트가 맵 경계에 막히지 않게 합니다.
     */
    public void releasePlayer(
            Match match,
            UUID playerId,
            boolean goToLobby
    ) {
        requireMainThread();

        byPlayer.remove(playerId, match.id());

        if (match.restored(playerId)) {
            return;
        }

        if (!match.captured(playerId)) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);

        if (player == null || player.isDead()) {
            // 스냅샷은 남겨 다음 접속 또는 리스폰 때 복원합니다.
            return;
        }

        try {
            boolean restored = states.restore(
                    player,
                    !goToLobby,
                    lobby.location()
            );

            if (restored || !states.hasSnapshot(playerId)) {
                match.markRestored(playerId);

                if (goToLobby) {
                    lobby.sendToLobby(player);
                }
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    player.getName()
                            + "님 경기 종료 상태 복원 실패. "
                            + "스냅샷을 보존합니다.",
                    exception
            );

            if (!closing && player.isOnline()) {
                player.kick(
                        Component.text(
                                "원래 플레이어 상태 복원에 실패했습니다.\n"
                                        + "관리자에게 문의하세요.",
                                NamedTextColor.RED
                        )
                );
            }
        }
    }

    /**
     * 플러그인 비활성화 시 먼저 호출합니다.
     * 정상 종료에서는 참가자의 원래 위치까지 복원합니다.
     */
    public void shutdown() {
        requireMainThread();
        closing = true;

        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }

        for (Match match : new ArrayList<>(matches.values())) {
            complete(match);
        }

        matches.clear();
        byPlayer.clear();
        maps.clearReservations();

        states.restoreAllOnline(lobby.location());
    }

    private void tickAll() {
        if (closing) {
            return;
        }

        for (Match match : new ArrayList<>(matches.values())) {
            if (matches.get(match.id()) != match) {
                continue;
            }

            try {
                match.tick();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "경기 실행 오류. 안전하게 중단합니다: "
                                + match.id(),
                        exception
                );

                complete(match);
            }
        }
    }

    private void onRoomDeparture(
            UUID matchId,
            UUID playerId
    ) {
        Match match = find(matchId);

        if (match != null) {
            match.depart(playerId);
        }
    }

    private Match requireMatch(Player player) {
        Match match = of(player);

        if (match == null || !match.isPlaying(player.getUniqueId())) {
            throw new IllegalArgumentException(
                    "참가 중인 경기가 없습니다."
            );
        }

        return match;
    }

    private void removeOrphanedItems(Player player) {
        ItemStack[] contents = player.getInventory().getContents();

        for (int index = 0; index < contents.length; index++) {
            if (items.isPluginItem(contents[index])) {
                player.getInventory().setItem(index, null);
            }
        }

        if (items.isPluginItem(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    private void requireOpen() {
        if (closing) {
            throw new IllegalArgumentException(
                    "플러그인이 종료 중입니다."
            );
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "경기 관리는 메인 스레드에서 실행해야 합니다."
            );
        }
    }
}