package val.code.party;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import val.code.config.Settings;
import val.code.map.ArenaMap;
import val.code.map.MapRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class RoomService {

    private final Settings settings;
    private final MapRepository maps;

    private final Map<UUID, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, UUID> membership = new HashMap<>();

    private BiConsumer<UUID, UUID> departureHandler;
    private boolean closing;

    public RoomService(Settings settings, MapRepository maps) {
        this.settings = settings;
        this.maps = maps;
    }

    /**
     * 인자 순서: 경기 UUID, 퇴장 플레이어 UUID.
     * 메인 클래스에서 MatchService의 퇴장 처리에 연결합니다.
     */
    public void setDepartureHandler(
            BiConsumer<UUID, UUID> departureHandler
    ) {
        requireMainThread();
        this.departureHandler = departureHandler;
    }

    public Room find(UUID roomId) {
        return rooms.get(roomId);
    }

    public Room of(UUID playerId) {
        UUID roomId = membership.get(playerId);
        return roomId == null ? null : rooms.get(roomId);
    }

    public Room of(Player player) {
        return of(player.getUniqueId());
    }

    public Collection<Room> all() {
        return rooms.values().stream()
                .sorted(Comparator.comparing(Room::name)
                        .thenComparing(room -> room.id().toString()))
                .toList();
    }

    public List<Room> publicRooms() {
        return all().stream()
                .filter(Room::publiclyVisible)
                .toList();
    }

    public Room create(Player creator, String rawName) {
        requireMainThread();
        requireOpen();

        if (of(creator) != null) {
            throw new IllegalArgumentException(
                    "이미 다른 방에 참가하고 있습니다."
            );
        }

        int maximumRooms = settings.integer(
                "rooms.maximum-rooms", 40, 1, 500
        );

        if (rooms.size() >= maximumRooms) {
            throw new IllegalArgumentException(
                    "현재 생성할 수 있는 방 수가 가득 찼습니다."
            );
        }

        String name = validName(rawName);
        int maximumCapacity = maximumCapacity();
        int defaultCapacity = settings.integer(
                "rooms.default-capacity", 10, 2, maximumCapacity
        );

        Room room = new Room(
                UUID.randomUUID(),
                creator.getUniqueId(),
                creator.getName(),
                name,
                defaultCapacity,
                settings.bool("rooms.default-public", true)
        );

        rooms.put(room.id(), room);
        membership.put(creator.getUniqueId(), room.id());

        List<ArenaMap> candidates = maps.all().stream()
                .filter(map -> map.problems(true).isEmpty())
                .toList();

        if (!candidates.isEmpty()) {
            room.mapId(candidates.getFirst().id());
        }

        message(
                creator,
                "'" + name + "' 방을 만들었습니다.",
                NamedTextColor.AQUA
        );

        return room;
    }

    public Room join(Player player, UUID roomId) {
        requireMainThread();
        requireOpen();

        if (of(player) != null) {
            throw new IllegalArgumentException(
                    "한 플레이어는 한 방에만 참가할 수 있습니다."
            );
        }

        Room room = requireRoom(roomId);

        if (!room.publiclyVisible()) {
            throw new IllegalArgumentException(
                    "비공개 방에는 공개방 목록으로 참가할 수 없습니다."
            );
        }

        if (room.state() != Room.State.WAITING) {
            throw new IllegalArgumentException(
                    "경기 중인 방에는 참가할 수 없습니다."
            );
        }

        if (room.size() >= room.capacity()) {
            throw new IllegalArgumentException(
                    "방 정원이 가득 찼습니다."
            );
        }

        room.add(player.getUniqueId(), player.getName());
        membership.put(player.getUniqueId(), room.id());

        broadcast(
                room,
                player.getName() + "님이 참가했습니다. "
                        + "(" + room.size() + "/" + room.capacity() + ")",
                NamedTextColor.AQUA
        );

        return room;
    }

    public void rename(Player actor, String rawName) {
        requireMainThread();
        Room room = requireWaitingLeader(actor);
        String previous = room.name();

        room.name(validName(rawName));

        broadcast(
                room,
                "방 이름이 '" + previous + "'에서 '"
                        + room.name() + "'로 변경되었습니다.",
                NamedTextColor.GRAY
        );
    }

    public void setCapacity(Player actor, int value) {
        requireMainThread();
        Room room = requireWaitingLeader(actor);

        if (value < 2 || value > maximumCapacity()) {
            throw new IllegalArgumentException(
                    "최대 인원은 2~" + maximumCapacity() + "명이어야 합니다."
            );
        }

        if (value < room.size()) {
            throw new IllegalArgumentException(
                    "현재 참가자 수보다 작은 정원을 설정할 수 없습니다."
            );
        }

        room.capacity(value);

        broadcast(
                room,
                "최대 인원이 " + value + "명으로 변경되었습니다.",
                NamedTextColor.GRAY
        );
    }

    public void setMap(Player actor, String mapId) {
        requireMainThread();
        Room room = requireWaitingLeader(actor);

        ArenaMap map = maps.require(mapId);

        if (!map.enabled()) {
            throw new IllegalArgumentException(
                    "비활성화된 맵은 선택할 수 없습니다."
            );
        }

        List<String> problems = map.problems(true);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "맵 설정이 완료되지 않았습니다:\n- "
                            + String.join("\n- ", problems)
            );
        }

        room.mapId(map.id());

        broadcast(
                room,
                "선택한 맵: " + map.displayName(),
                NamedTextColor.AQUA
        );
    }

    public void setPublic(Player actor, boolean publiclyVisible) {
        requireMainThread();
        Room room = requireWaitingLeader(actor);

        room.publiclyVisible(publiclyVisible);

        broadcast(
                room,
                "방 공개 상태: "
                        + (publiclyVisible ? "공개" : "비공개"),
                NamedTextColor.GRAY
        );
    }

    public void transfer(Player actor, UUID target) {
        requireMainThread();
        Room room = requireLeader(actor);

        if (!room.contains(target)) {
            throw new IllegalArgumentException(
                    "방에 있는 참가자를 선택해야 합니다."
            );
        }

        if (target.equals(room.leader())) {
            throw new IllegalArgumentException(
                    "이미 방장인 플레이어입니다."
            );
        }

        Player onlineTarget = Bukkit.getPlayer(target);

        if (onlineTarget == null || !onlineTarget.isOnline()) {
            throw new IllegalArgumentException(
                    "접속 중인 참가자에게만 방장을 넘길 수 있습니다."
            );
        }

        room.leader(target);

        broadcast(
                room,
                room.memberName(target) + "님이 새 방장이 되었습니다.",
                NamedTextColor.RED
        );
    }

    public void kick(Player actor, UUID target) {
        requireMainThread();
        Room room = requireLeader(actor);

        if (target.equals(actor.getUniqueId())) {
            throw new IllegalArgumentException(
                    "자신을 추방할 수 없습니다. 방 나가기를 사용하세요."
            );
        }

        if (!room.contains(target)) {
            throw new IllegalArgumentException(
                    "해당 플레이어는 이 방의 참가자가 아닙니다."
            );
        }

        String name = room.memberName(target);

        broadcast(
                room,
                name + "님이 방장에 의해 추방되었습니다.",
                NamedTextColor.RED
        );

        Player targetPlayer = Bukkit.getPlayer(target);

        if (targetPlayer != null) {
            message(
                    targetPlayer,
                    "방장에서 추방되었습니다.",
                    NamedTextColor.RED
            );
        }

        removeMember(room, target);
    }

    /**
     * 일반 퇴장과 접속 종료 모두 이 경로를 사용합니다.
     */
    public void leave(UUID playerId) {
        requireMainThread();

        Room room = of(playerId);

        if (room == null) {
            return;
        }

        String name = room.memberName(playerId);

        broadcast(
                room,
                name + "님이 방을 나갔습니다.",
                NamedTextColor.GRAY
        );

        removeMember(room, playerId);
    }

    public void disband(Player actor) {
        requireMainThread();
        Room room = requireWaitingLeader(actor);

        broadcast(
                room,
                "방장이 방을 해산했습니다.",
                NamedTextColor.RED
        );

        deleteWaitingRoom(room);
    }

    /**
     * 실제 맵 예약과 경기 객체 준비를 완료한 뒤 호출합니다.
     */
    public void beginMatch(UUID roomId, UUID matchId) {
        requireMainThread();
        requireOpen();

        Room room = requireRoom(roomId);

        if (room.state() != Room.State.WAITING) {
            throw new IllegalArgumentException(
                    "이미 시작한 방입니다."
            );
        }

        if (matchId == null) {
            throw new IllegalArgumentException(
                    "경기 식별자가 필요합니다."
            );
        }

        if (departureHandler == null) {
            throw new IllegalStateException(
                    "경기 퇴장 처리기가 연결되지 않았습니다."
            );
        }

        int minimum = settings.integer(
                "rooms.minimum-players", 2, 2, maximumCapacity()
        );

        if (room.size() < minimum) {
            throw new IllegalArgumentException(
                    "경기 시작에 최소 " + minimum + "명이 필요합니다."
            );
        }

        for (UUID member : room.memberIds()) {
            Player player = Bukkit.getPlayer(member);

            if (player == null || !player.isOnline()) {
                throw new IllegalArgumentException(
                        room.memberName(member) + "님이 접속 중이 아닙니다."
                );
            }
        }

        room.beginMatch(matchId);
    }

    /**
     * 정상 종료 또는 시작 취소 후 호출합니다.
     * 경기 종료 후에도 남은 참가자들의 파티는 유지됩니다.
     */
    public void finishMatch(UUID roomId, UUID matchId) {
        requireMainThread();

        Room room = find(roomId);

        if (room == null) {
            return;
        }

        room.finishMatch(matchId);

        if (room.size() == 0) {
            rooms.remove(room.id());
        }
    }

    public void broadcast(
            Room room,
            String text,
            NamedTextColor color
    ) {
        Component component = Component.text("[파티] ", NamedTextColor.RED)
                .append(Component.text(text, color));

        for (UUID member : room.memberIds()) {
            Player player = Bukkit.getPlayer(member);

            if (player != null && player.isOnline()) {
                player.sendMessage(component);
            }
        }
    }

    /**
     * MatchService를 먼저 종료한 후 플러그인 비활성화 시 호출합니다.
     */
    public void shutdown() {
        requireMainThread();
        closing = true;
        rooms.clear();
        membership.clear();
    }

    private void removeMember(Room room, UUID playerId) {
        UUID matchId = room.matchId();
        boolean wasLeader = room.isLeader(playerId);

        room.remove(playerId);
        membership.remove(playerId, room.id());

        if (room.size() == 0) {
            rooms.remove(room.id());
        } else if (wasLeader) {
            UUID newLeader = room.memberIds().getFirst();
            room.leader(newLeader);

            broadcast(
                    room,
                    room.memberName(newLeader)
                            + "님에게 방장이 자동으로 넘어갔습니다.",
                    NamedTextColor.RED
            );
        }

        if (matchId != null) {
            if (departureHandler == null) {
                throw new IllegalStateException(
                        "경기 중인 방에 퇴장 처리기가 없습니다."
                );
            }

            departureHandler.accept(matchId, playerId);
        }
    }

    private void deleteWaitingRoom(Room room) {
        for (UUID member : new ArrayList<>(room.memberIds())) {
            membership.remove(member, room.id());
        }

        rooms.remove(room.id());
    }

    private Room requireRoom(UUID roomId) {
        Room room = rooms.get(roomId);

        if (room == null) {
            throw new IllegalArgumentException(
                    "방이 존재하지 않거나 이미 삭제되었습니다."
            );
        }

        return room;
    }

    private Room requireLeader(Player actor) {
        requireOpen();

        Room room = of(actor);

        if (room == null) {
            throw new IllegalArgumentException(
                    "참가 중인 방이 없습니다."
            );
        }

        if (!room.isLeader(actor.getUniqueId())) {
            throw new IllegalArgumentException(
                    "방장만 사용할 수 있는 기능입니다."
            );
        }

        return room;
    }

    private Room requireWaitingLeader(Player actor) {
        Room room = requireLeader(actor);

        if (room.state() != Room.State.WAITING) {
            throw new IllegalArgumentException(
                    "경기 중에는 방 설정 변경이나 해산을 할 수 없습니다."
            );
        }

        return room;
    }

    private int maximumCapacity() {
        return settings.integer(
                "rooms.maximum-capacity", 10, 2, 20
        );
    }

    private void requireOpen() {
        if (closing) {
            throw new IllegalArgumentException(
                    "플러그인이 종료 중입니다."
            );
        }
    }

    private static String validName(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "방 이름을 입력하세요."
            );
        }

        String name = value.strip();

        if (name.isEmpty() || name.length() > 32) {
            throw new IllegalArgumentException(
                    "방 이름은 1~32자여야 합니다."
            );
        }

        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "방 이름에 제어 문자를 사용할 수 없습니다."
            );
        }

        return name;
    }

    private static void message(
            Player player,
            String text,
            NamedTextColor color
    ) {
        player.sendMessage(
                Component.text("[VALO] ", NamedTextColor.RED)
                        .append(Component.text(text, color))
        );
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "파티 변경은 메인 스레드에서 실행해야 합니다."
            );
        }
    }
}