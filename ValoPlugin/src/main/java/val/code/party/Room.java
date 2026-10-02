package val.code.party;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Room {

    public enum State {
        WAITING,
        IN_MATCH
    }

    private final UUID id;
    private final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();

    private UUID leader;
    private String name;
    private int capacity;
    private String mapId;
    private boolean publiclyVisible;
    private State state = State.WAITING;
    private UUID matchId;

    Room(
            UUID id,
            UUID leader,
            String leaderName,
            String name,
            int capacity,
            boolean publiclyVisible
    ) {
        this.id = id;
        this.leader = leader;
        this.name = name;
        this.capacity = capacity;
        this.publiclyVisible = publiclyVisible;
        members.put(leader, leaderName);
    }

    public UUID id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public String name() {
        return name;
    }

    public int capacity() {
        return capacity;
    }

    public String mapId() {
        return mapId;
    }

    public boolean publiclyVisible() {
        return publiclyVisible;
    }

    public State state() {
        return state;
    }

    public UUID matchId() {
        return matchId;
    }

    public int size() {
        return members.size();
    }

    public boolean contains(UUID player) {
        return members.containsKey(player);
    }

    public boolean isLeader(UUID player) {
        return leader.equals(player);
    }

    public boolean joinable() {
        return state == State.WAITING && size() < capacity;
    }

    public List<UUID> memberIds() {
        return List.copyOf(members.keySet());
    }

    public Map<UUID, String> members() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(members)
        );
    }

    public String memberName(UUID player) {
        return members.getOrDefault(player, "알 수 없는 플레이어");
    }

    public String statusText() {
        if (state == State.IN_MATCH) {
            return "경기 진행 중";
        }
        if (size() >= capacity) {
            return "정원 초과 · 참가 불가";
        }
        return "참가 가능";
    }

    void add(UUID player, String actualName) {
        members.put(player, actualName);
    }

    void remove(UUID player) {
        members.remove(player);
    }

    void leader(UUID player) {
        if (!members.containsKey(player)) {
            throw new IllegalArgumentException(
                    "방 안에 있는 참가자에게만 방장을 넘길 수 있습니다."
            );