package val.code.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import val.code.config.Settings;
import val.code.game.Fighter;
import val.code.game.Match;
import val.code.game.MatchPhase;
import val.code.game.MatchService;
import val.code.map.ArenaMap;
import val.code.map.MapRepository;
import val.code.party.Room;
import val.code.party.RoomService;
import val.code.sound.SoundService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

public final class Menus implements Listener {

    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final Settings settings;
    private final RoomService rooms;
    private final MapRepository maps;
    private final MatchService matches;
    private final SoundService sounds;

    public Menus(
            JavaPlugin plugin,
            Settings settings,
            RoomService rooms,
            MapRepository maps,
            MatchService matches,
            SoundService sounds
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.rooms = rooms;
        this.maps = maps;
        this.matches = matches;
        this.sounds = sounds;
    }

    /**
     * 메인 클래스에서 MatchService.setScreenHandler()에 연결합니다.
     */
    public void openMatchScreen(
            Player player,
            MatchService.Screen screen
    ) {
        switch (screen) {
            case SELECT -> openAgent(player);
            case SHOP -> openShop(player);
            case SPECTATE -> openSpectate(player, 0);
            case RESULT -> openResult(player);
        }
    }

    public void openMain(Player player) {
        Match match = matches.of(player);

        if (match != null) {
            openCurrentMatchScreen(player, match);
            return;
        }

        Menu menu = menu(player, 27, "VALO · 메인 메뉴");

        button(menu, 11, item(
                Material.COMPASS,
                "공개방 목록",
                NamedTextColor.AQUA,
                "공개된 방을 확인합니다.",
                "대기 중이며 빈자리가 있는 방에 참가할 수 있습니다."
        ), () -> openPublicRooms(player, 0));

        Room room = rooms.of(player);

        if (room == null) {
            button(menu, 13, item(
                    Material.EMERALD,
                    "새 방 만들기",
                    NamedTextColor.AQUA,
                    "기본 이름으로 방을 만듭니다.",
                    "이름: " + player.getName() + "의 방",
                    "생성 후 /valo rename <이름>으로 변경합니다."
            ), () -> {
                rooms.create(player, player.getName() + "의 방");
                openRoom(player);
            });
        } else {
            button(menu, 13, item(
                    Material.OAK_DOOR,
                    "내 방 · " + room.name(),
                    NamedTextColor.AQUA,
                    "인원: " + room.size() + "/" + room.capacity(),
                    "방장: " + room.memberName(room.leader()),
                    "내 방 화면을 엽니다."
            ), () -> openRoom(player));
        }

        button(menu, 15, item(
                Material.RED_BED,
                "로비로 이동",
                NamedTextColor.RED,
                "메인 로비로 이동합니다.",
                "대기 중인 방 소속은 유지됩니다."
        ), () -> {
            player.closeInventory();
            matches.returnToLobby(player);
        });

        if (player.hasPermission("valo.admin")) {
            button(menu, 22, item(
                    Material.COMMAND_BLOCK,
                    "관리자 정보",
                    NamedTextColor.RED,
                    "등록된 맵과 진행 중인 경기를 확인합니다.",
                    "관리자 명령은 다음 부에서 연결됩니다."
            ), () -> openAdmin(player));
        }

        show(player, menu);
    }

    public void openPublicRooms(Player player, int requestedPage) {
        List<Room> list = rooms.publicRooms();
        int page = clampPage(requestedPage, list.size());

        Menu menu = menu(
                player,
                54,
                "공개방 · " + (page + 1) + "/" + pageCount(list.size())
        );

        int start = page * PAGE_SIZE;
        int end = Math.min(list.size(), start + PAGE_SIZE);

        for (int index = start; index < end; index++) {
            Room room = list.get(index);
            UUID roomId = room.id();

            ArenaMap map = findMap(room.mapId());
            String mapName = map == null
                    ? "선택하지 않음" : map.displayName();

            Material icon = room.state() == Room.State.IN_MATCH
                    ? Material.IRON_BARS
                    : room.joinable()
                        ? Material.LIME_CONCRETE
                        : Material.RED_CONCRETE;

            button(menu, index - start, item(
                    icon,
                    room.name(),
                    room.joinable()
                            ? NamedTextColor.AQUA : NamedTextColor.RED,
                    "방장: " + room.memberName(room.leader()),
                    "인원: " + room.size() + "/" + room.capacity(),
                    "맵: " + mapName,
                    "상태: " + room.statusText(),
                    "클릭하여 참가"
            ), () -> {
                // RoomService에서 공개 상태·정원·진행 상태를 재검사합니다.
                rooms.join(player, roomId);
                openRoom(player);
            });
        }

        if (list.isEmpty()) {
            decoration(menu, 22, item(
                    Material.BARRIER,
                    "공개방이 없습니다",
                    NamedTextColor.GRAY,
                    "메인 메뉴에서 새 방을 만들 수 있습니다."
            ));
        }

        pagination(
                menu,
                page,
                list.size(),
                next -> openPublicRooms(player, next),
                () -> openMain(player)
        );

        show(player, menu);
    }

    public void openRoom(Player player) {
        Room room = requireRoom(player);
        UUID roomId = room.id();

        Menu menu = menu(player, 45, "내 방 · " + room.name());

        decoration(menu, 4, item(
                Material.OAK_SIGN,
                room.name(),
                NamedTextColor.WHITE,
                "방장: " + room.memberName(room.leader()),
                "인원: " + room.size() + "/" + room.capacity(),
                "공개 상태: " + (room.publiclyVisible() ? "공개" : "비공개"),
                "상태: " + room.statusText()
        ));

        button(menu, 11, item(
                Material.PLAYER_HEAD,
                "참가자 목록",
                NamedTextColor.AQUA,
                "실제 참가자 이름을 확인합니다.",
                "방장은 위임과 추방을 할 수 있습니다."
        ), () -> {
            requireSameRoom(player, roomId);
            openMembers(player, 0);
        });

        ArenaMap selectedMap = findMap(room.mapId());

        button(menu, 13, item(
                Material.FILLED_MAP,
                "맵 · " + (selectedMap == null
                        ? "선택하지 않음" : selectedMap.displayName()),
                NamedTextColor.AQUA,
                "방장은 대기 중인 방의 맵을 변경할 수 있습니다."
        ), () -> {
            requireSameRoom(player, roomId);
            openMaps(player, 0);
        });

        button(menu, 15, item(
                Material.REPEATER,
                "방 설정",
                NamedTextColor.AQUA,
                "정원과 공개 상태를 변경합니다.",
                "방장 전용"
        ), () -> {
            requireSameRoom(player, roomId);
            openRoomSettings(player);
        });

        button(menu, 22, item(
                Material.DIAMOND_SWORD,
                "경기 시작",
                NamedTextColor.RED,
                "방장이 경기를 시작합니다.",
                "모든 참가자는 접속 중이어야 합니다.",
                "맵과 메인 로비 설정이 필요합니다."
        ), () -> {
            requireSameRoom(player, roomId);
            matches.start(player);
            player.closeInventory();
        });

        button(menu, 30, item(
                Material.RED_BED,
                "방 나가기",
                NamedTextColor.RED,
                "방 소속에서 나갑니다.",
                "방장이 나가면 다음 참가자에게 방장이 넘어갑니다."
        ), () -> {
            requireSameRoom(player, roomId);
            rooms.leave(player.getUniqueId());
            openMain(player);
        });

        button(menu, 32, item(
                Material.TNT,
                "방 해산",
                NamedTextColor.RED,
                "대기 중인 방만 해산할 수 있습니다.",
                "방장 전용 · 다음 화면에서 확인"
        ), () -> {
            requireSameRoom(player, roomId);
            openDisbandConfirmation(player, roomId);
        });

        button(menu, 40, backItem(), () -> openMain(player));

        show(player, menu);
    }

    public void openRoomSettings(Player player) {
        Room room = requireWaitingLeader(player);
        UUID roomId = room.id();

        int maximum = settings.integer(
                "rooms.maximum-capacity", 10, 2, 20
        );

        Menu menu = menu(player, 27, "방 설정");

        button(menu, 10, item(
                Material.REDSTONE,
                "정원 줄이기",
                NamedTextColor.RED,
                "현재 정원: " + room.capacity(),
                "현재 참가자 수보다 줄일 수 없습니다.",
                "최소 정원: 2"
        ), () -> {
            Room current = requireSameRoom(player, roomId);
            rooms.setCapacity(player, current.capacity() - 1);
            openRoomSettings(player);
        });

        decoration(menu, 13, item(
                Material.PAPER,
                "정원 " + room.capacity(),
                NamedTextColor.WHITE,
                "현재 인원: " + room.size(),
                "설정 가능 범위: 2~" + maximum,
                "이름 변경: /valo rename <이름>"
        ));

        button(menu, 16, item(
                Material.GLOWSTONE_DUST,
                "정원 늘리기",
                NamedTextColor.AQUA,
                "현재 정원: " + room.capacity(),
                "최대 정원: " + maximum
        ), () -> {
            Room current = requireSameRoom(player, roomId);
            rooms.setCapacity(player, current.capacity() + 1);
            openRoomSettings(player);
        });

        button(menu, 22, item(
                room.publiclyVisible()
                        ? Material.LIME_DYE : Material.GRAY_DYE,
                "공개 상태 · "
                        + (room.publiclyVisible() ? "공개" : "비공개"),
                NamedTextColor.AQUA,
                "클릭하여 공개 상태를 전환합니다.",
                "비공개 방은 공개방 목록에 표시되지 않습니다.",
                "현재 구현에는 초대 기능이 없습니다."
        ), () -> {
            Room current = requireSameRoom(player, roomId);
            rooms.setPublic(player, !current.publiclyVisible());
            openRoomSettings(player);
        });

        button(menu, 18, backItem(), () -> openRoom(player));

        show(player, menu);
    }

    public void openMaps(Player player, int requestedPage) {
        Room room = requireWaitingLeader(player);
        UUID roomId = room.id();

        List<ArenaMap> list = new ArrayList<>(maps.all());
        int page = clampPage(requestedPage, list.size());

        Menu menu = menu(
                player,
                54,
                "맵 선택 · " + (page + 1) + "/" + pageCount(list.size())
        );

        int start = page * PAGE_SIZE;
        int end = Math.min(list.size(), start + PAGE_SIZE);

        for (int index = start; index < end; index++) {
            ArenaMap map = list.get(index);
            String mapId = map.id();

            List<String> problems = map.problems(true);
            boolean ready = map.enabled() && problems.isEmpty();

            List<String> lore = new ArrayList<>();
            lore.add("ID: " + mapId);
            lore.add("활성화: " + (map.enabled() ? "예" : "아니오"));
            lore.add("검증: " + (ready ? "사용 가능" : "사용 불가"));

            for (String problem : problems.stream().limit(5).toList()) {
                lore.add("· " + problem);
            }

            if (problems.size() > 5) {
                lore.add("외 " + (problems.size() - 5) + "개 문제");
            }

            lore.add("클릭하여 선택");

            button(menu, index - start, item(
                    ready ? Material.FILLED_MAP : Material.BARRIER,
                    map.displayName(),
                    ready ? NamedTextColor.AQUA : NamedTextColor.RED,
                    lore.toArray(String[]::new)
            ), () -> {
                requireSameRoom(player, roomId);
                rooms.setMap(player, mapId);
                openRoom(player);
            });
        }

        if (list.isEmpty()) {
            decoration(menu, 22, item(
                    Material.BARRIER,
                    "등록된 맵이 없습니다",
                    NamedTextColor.RED,
                    "관리자가 먼저 맵을 등록해야 합니다."
            ));
        }

        pagination(
                menu,
                page,
                list.size(),
                next -> openMaps(player, next),
                () -> openRoom(player)
        );

        show(player, menu);
    }

    public void openMembers(Player player, int requestedPage) {
        Room room = requireRoom(player);
        UUID roomId = room.id();

        List<UUID> members = room.memberIds();
        int page = clampPage(requestedPage, members.size());

        Menu menu = menu(
                player,
                54,
                "참가자 · " + (page + 1) + "/" + pageCount(members.size())
        );

        int start = page * PAGE_SIZE;
        int end = Math.min(members.size(), start + PAGE_SIZE);

        for (int index = start; index < end; index++) {
            UUID target = members.get(index);
            boolean leader = room.isLeader(target);

            button(menu, index - start, item(
                    leader ? Material.GOLDEN_HELMET : Material.PLAYER_HEAD,
                    room.memberName(target),
                    leader ? NamedTextColor.RED : NamedTextColor.WHITE,
                    "역할: " + (leader ? "방장" : "참가자"),
                    "접속: " + (Bukkit.getPlayer(target) != null
                            ? "접속 중" : "접속 종료"),
                    "방장은 클릭하여 위임·추방 메뉴를 엽니다."
            ), () -> {
                requireSameRoom(player, roomId);
                openMemberManagement(player, roomId, target);
            });
        }

        pagination(
                menu,
                page,
                members.size(),
                next -> openMembers(player, next),
                () -> openRoom(player)
        );

        show(player, menu);
    }

    private void openMemberManagement(
            Player player,
            UUID roomId,
            UUID targetId
    ) {
        Room room = requireLeader(player);

        if (!room.id().equals(roomId) || !room.contains(targetId)) {
            throw new IllegalArgumentException(
                    "해당 참가자가 더 이상 이 방에 없습니다."
            );
        }

        if (player.getUniqueId().equals(targetId)) {
            throw new IllegalArgumentException(
                    "자신에게 위임하거나 자신을 추방할 수 없습니다."
            );
        }

        Menu menu = menu(
                player, 27, "참가자 관리 · " + room.memberName(targetId)
        );

        button(menu, 11, item(
                Material.GOLDEN_HELMET,
                "방장 위임",
                NamedTextColor.RED,
                "선택한 참가자에게 방장을 넘깁니다.",
                "클릭하면 바로 적용됩니다."
        ), () -> {
            requireSameRoom(player, roomId);
            rooms.transfer(player, targetId);
            openRoom(player);
        });

        button(menu, 15, item(
                Material.BARRIER,
                "추방",
                NamedTextColor.RED,
                "선택한 참가자를 방에서 내보냅니다.",
                "클릭하면 바로 적용됩니다."
        ), () -> {
            requireSameRoom(player, roomId);
            rooms.kick(player, targetId);
            openMembers(player, 0);
        });

        button(menu, 22, backItem(), () -> openMembers(player, 0));

        show(player, menu);
    }

    private void openDisbandConfirmation(
            Player player,
            UUID roomId
    ) {
        requireWaitingLeader(player);
        requireSameRoom(player, roomId);

        Menu menu = menu(player, 27, "방 해산 확인");

        button(menu, 11, item(
                Material.TNT,
                "해산 확인",
                NamedTextColor.RED,
                "모든 참가자의 방 소속을 해제합니다.",
                "되돌릴 수 없습니다."
        ), () -> {
            requireSameRoom(player, roomId);
            rooms.disband(player);
            openMain(player);
        });

        button(menu, 15, item(
                Material.LIME_DYE,
                "취소",
                NamedTextColor.AQUA,
                "내 방으로 돌아갑니다."
        ), () -> openRoom(player));

        show(player, menu);
    }

    public void openAgent(Player player) {
        Match match = requireMatch(player);

        if (match.phase() != MatchPhase.SELECTING) {
            throw new IllegalArgumentException(
                    "현재 요원 선택 단계가 아닙니다."
            );
        }

        UUID matchId = match.id();
        Fighter fighter = match.fighter(player.getUniqueId());

        Menu menu = menu(player, 27, "요원 선택 · 제트");

        decoration(menu, 4, item(
                Material.PAPER,
                "선택 제한 시간 · " + match.remainingSeconds() + "초",
                NamedTextColor.WHITE,
                "팀: " + fighter.team().korean(),
                "역할: " + fighter.team().roleName(match.attackingTeam()),
                "현재 구현된 요원은 제트 한 명입니다.",
                "GUI 시간 표시는 열었을 때 기준입니다.",
                "실시간 남은 시간은 보스바에서 확인하세요."
        ));

        boolean selected = fighter.selectedAgent() == Fighter.Agent.JETT;

        button(menu, 11, item(
                Material.FEATHER,
                "제트" + (selected ? " · 선택됨" : ""),
                NamedTextColor.AQUA,
                "대시: 짧은 거리 수평 이동",
                "연막: 조준 위치에 구체 연막 생성",
                "상태: " + (fighter.agentConfirmed()
                        ? "확정 완료" : selected ? "선택됨" : "미선택"),
                "클릭하여 선택"
        ), () -> requireSameMatch(player, matchId).selectJett(player));

        button(menu, 15, item(
                fighter.agentConfirmed()
                        ? Material.LIME_CONCRETE : Material.LIME_DYE,
                fighter.agentConfirmed() ? "확정 완료" : "선택 확정",
                NamedTextColor.AQUA,
                "먼저 제트 아이콘을 선택하세요.",
                "확정 후에는 변경할 수 없습니다.",
                "모두 확정하면 시작 카운트다운으로 이동합니다."
        ), () -> requireSameMatch(player, matchId).confirmAgent(player));

        button(menu, 22, item(
                Material.OAK_DOOR,
                "화면 닫기",
                NamedTextColor.GRAY,
                "/valo agent로 다시 열 수 있습니다.",
                "화면을 닫아도 선택 제한 시간은 계속 진행됩니다."
        ), player::closeInventory);

        show(player, menu);
    }

    public void openShop(Player player) {
        Match match = requireMatch(player);

        if (!match.canBuy(player)) {
            throw new IllegalArgumentException(
                    "구매 단계의 팀 스폰 근처에서만 상점을 열 수 있습니다."
            );
        }

        UUID matchId = match.id();
        Fighter fighter = match.fighter(player.getUniqueId());

        int armorPrice = settings.integer(
                "economy.armor-price", 400, 0, 1_000_000
        );
        int dashPrice = settings.integer(
                "economy.dash-charge-price", 200, 0, 1_000_000
        );
        int smokePrice = settings.integer(
                "economy.smoke-charge-price", 200, 0, 1_000_000
        );
        int maximumDash = settings.integer(
                "jett.dash.maximum-charges", 2, 1, 10
        );
        int maximumSmoke = settings.integer(
                "jett.smoke.maximum-charges", 3, 1, 10
        );

        Menu menu = menu(player, 27, "구매 상점");

        decoration(menu, 4, item(
                Material.EMERALD,
                "잔액 · " + fighter.money() + " 크레딧",
                NamedTextColor.AQUA,
                "구매 단계에서만 사용할 수 있습니다.",
                "각 상품은 라운드당 한 번만 구매할 수 있습니다."
        ));

        decoration(menu, 10, item(
                Material.IRON_HORSE_ARMOR,
                "클래식 · 기본 지급",
                NamedTextColor.WHITE,
                "별도 구매가 필요하지 않습니다.",
                "우클릭: 발사",
                "F키: 재장전"
        ));

        button(menu, 12, item(
                Material.IRON_CHESTPLATE,
                "방어구 · " + armorPrice + " 크레딧",
                NamedTextColor.AQUA,
                "피해 일부를 방어구가 흡수합니다.",
                "상태: " + (fighter.armorPurchased()
                        ? "이번 라운드 구매 완료" : "구매 가능"),
                "현재 방어구: " + (int) Math.ceil(fighter.armor())
        ), () -> requireSameMatch(player, matchId).buyArmor(player));

        button(menu, 14, item(
                Material.FEATHER,
                "대시 추가 충전 · " + dashPrice + " 크레딧",
                NamedTextColor.AQUA,
                "충전: " + fighter.dashCharges() + "/" + maximumDash,
                "상태: " + (fighter.dashPurchased()
                        ? "이번 라운드 구매 완료" : "미구매"),
                "구매 시 충전 1개 추가"
        ), () -> requireSameMatch(player, matchId).buyDash(player));

        button(menu, 16, item(
                Material.SNOWBALL,
                "연막 추가 충전 · " + smokePrice + " 크레딧",
                NamedTextColor.AQUA,
                "충전: " + fighter.smokeCharges() + "/" + maximumSmoke,
                "상태: " + (fighter.smokePurchased()
                        ? "이번 라운드 구매 완료" : "미구매"),
                "구매 시 충전 1개 추가"
        ), () -> requireSameMatch(player, matchId).buySmoke(player));

        button(menu, 22, closeItem(), player::closeInventory);

        show(player, menu);
    }

    public void openSpectate(Player player, int requestedPage) {
        Match match = requireMatch(player);
        Fighter viewer = match.fighter(player.getUniqueId());

        if (viewer.alive() || !match.phase().isRoundActive()) {
            throw new IllegalArgumentException(
                    "라운드 중 사망한 참가자만 관전 대상을 선택할 수 있습니다."
            );
        }

        UUID matchId = match.id();

        List<Fighter> targets = match.fighters().stream()
                .filter(Fighter::alive)
                .filter(target -> match.player(target.id()) != null)
                .toList();

        int page = clampPage(requestedPage, targets.size());

        Menu menu = menu(
                player,
                54,
                "관전 대상 · " + (page + 1)
                        + "/" + pageCount(targets.size())
        );

        int start = page * PAGE_SIZE;
        int end = Math.min(targets.size(), start + PAGE_SIZE);

        for (int index = start; index < end; index++) {
            Fighter target = targets.get(index);
            UUID targetId = target.id();

            button(menu, index - start, item(
                    Material.PLAYER_HEAD,
                    target.name(),
                    target.team().color(),
                    "팀: " + target.team().korean(),
                    "역할: " + target.team().roleName(match.attackingTeam()),
                    "K/D: " + target.kills() + "/" + target.deaths(),
                    "클릭하여 관전",
                    "현재 규칙에서는 양 팀 생존자를 관전할 수 있습니다."
            ), () -> requireSameMatch(player, matchId)
                    .chooseSpectatorTarget(player, targetId));
        }

        if (targets.isEmpty()) {
            decoration(menu, 22, item(
                    Material.BARRIER,
                    "생존한 관전 대상이 없습니다",
                    NamedTextColor.GRAY,
                    "안전 관전자 위치에서 라운드 결과를 기다리세요."
            ));
        }

        pagination(
                menu,
                page,
                targets.size(),
                next -> openSpectate(player, next),
                player::closeInventory
        );

        show(player, menu);
    }

    public void openResult(Player player) {
        Match match = requireMatch(player);

        if (match.phase() != MatchPhase.RESULT) {
            throw new IllegalArgumentException(
                    "아직 경기 결과 단계가 아닙니다."
            );
        }

        UUID matchId = match.id();
        Fighter fighter = match.fighter(player.getUniqueId());
        boolean won = fighter.team() == match.winner();

        Menu menu = menu(player, 45, "경기 결과");

        decoration(menu, 4, item(
                won ? Material.DIAMOND : Material.REDSTONE,
                won ? "승리" : "패배",
                won ? NamedTextColor.AQUA : NamedTextColor.RED,
                "최종 점수: "
                        + match.score(val.code.game.Team.RED)
                        + " : "
                        + match.score(val.code.game.Team.CYAN),
                "승리 팀: " + (match.winner() == null
                        ? "없음" : match.winner().korean()),
                "종료 사유: " + match.resultReason()
        ));

        decoration(menu, 20, item(
                Material.IRON_SWORD,
                "내 전적",
                NamedTextColor.WHITE,
                "플레이어: " + fighter.name(),
                "팀: " + fighter.team().korean(),
                "킬: " + fighter.kills(),
                "데스: " + fighter.deaths(),
                "완료 라운드: " + match.completedRounds()
        ));

        decoration(menu, 24, item(
                Material.CLOCK,
                "자동 복귀 안내",
                NamedTextColor.GRAY,
                "남은 시간: " + match.remainingSeconds() + "초",
                "실시간 남은 시간은 보스바에서 확인하세요.",
                "복귀 시 원래 플레이어 상태를 복원합니다.",
                "정상 경기 종료 후에도 파티는 유지됩니다."
        ));

        button(menu, 31, item(
                Material.RED_BED,
                "로비로 복귀",
                NamedTextColor.AQUA,
                "지금 로비로 돌아갑니다.",
                "방 소속은 유지됩니다."
        ), () -> {
            requireSameMatch(player, matchId);
            matches.returnToLobby(player);
        });

        button(menu, 40, closeItem(), player::closeInventory);

        show(player, menu);
    }

    public void openAdmin(Player player) {
        requireAdmin(player);

        Menu menu = menu(player, 27, "관리자 정보");

        decoration(menu, 11, item(
                Material.FILLED_MAP,
                "등록된 맵 · " + maps.all().size(),
                NamedTextColor.AQUA,
                "맵 등록과 좌표 수정은 관리자 명령으로 수행합니다.",
                "명령 구현과 사용법은 다음 부에서 제공합니다."
        ));

        decoration(menu, 13, item(
                Material.DIAMOND_SWORD,
                "진행 중 경기 · " + matches.all().size(),
                NamedTextColor.RED,
                "경기 UUID와 상태는 아래 목록에서 확인합니다."
        ));

        button(menu, 15, item(
                Material.BOOK,
                "경기 목록",
                NamedTextColor.WHITE,
                "현재 진행 중인 경기 정보를 확인합니다."
        ), () -> openAdminMatches(player, 0));

        button(menu, 22, backItem(), () -> openMain(player));

        show(player, menu);
    }

    public void openAdminMatches(Player player, int requestedPage) {
        requireAdmin(player);

        List<Match> list = new ArrayList<>(matches.all());
        int page = clampPage(requestedPage, list.size());

        Menu menu = menu(
                player,
                54,
                "경기 목록 · " + (page + 1) + "/" + pageCount(list.size())
        );

        int start = page * PAGE_SIZE;
        int end = Math.min(list.size(), start + PAGE_SIZE);

        for (int index = start; index < end; index++) {
            Match match = list.get(index);

            decoration(menu, index - start, item(
                    Material.DIAMOND_SWORD,
                    match.map().displayName(),
                    NamedTextColor.WHITE,
                    "UUID: " + match.id(),
                    "상태: " + match.phase().korean(),
                    "라운드: " + match.roundNumber(),
                    "참가자: " + match.fighters().size(),
                    "정보 화면입니다. 클릭으로 종료하지 않습니다."
            ));
        }

        if (list.isEmpty()) {
            decoration(menu, 22, item(
                    Material.BARRIER,
                    "진행 중인 경기가 없습니다",
                    NamedTextColor.GRAY
            ));
        }

        pagination(
                menu,
                page,
                list.size(),
                next -> openAdminMatches(player, next),
                () -> openAdmin(player)
        );

        show(player, menu);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();

        if (!(top.getHolder() instanceof Menu menu)) {
            return;
        }

        // 아래쪽 플레이어 인벤토리까지 모두 잠급니다.
        // 쉬프트 이동, 숫자키 교환, 더블클릭 회수도 차단합니다.
        event.setCancelled(true);

        if (!menu.viewerId.equals(player.getUniqueId())) {
            return;
        }

        int rawSlot = event.getRawSlot();

        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }

        // 일반 왼쪽 클릭만 버튼 동작으로 인정합니다.
        if (event.getClick()
                != org.bukkit.event.inventory.ClickType.LEFT) {
            return;
        }

        Runnable action = menu.actions.get(rawSlot);

        if (action == null || menu.actionQueued) {
            return;
        }

        menu.actionQueued = true;

        // InventoryClickEvent 안에서 화면을 교체하지 않습니다.
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (!player.isOnline()
                        || player.getOpenInventory()
                        .getTopInventory().getHolder() != menu) {
                    return;
                }

                sounds.play(player, "menu-click");
                action.run();
            } catch (IllegalArgumentException exception) {
                player.sendMessage(
                        Component.text("[VALO] ", NamedTextColor.RED)
                                .append(Component.text(
                                        exception.getMessage() == null
                                                ? "현재 사용할 수 없습니다."
                                                : exception.getMessage(),
                                        NamedTextColor.RED
                                ))
                );
                sounds.play(player, "menu-error");
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "GUI 동작 실패: " + player.getName(),
                        exception
                );

                player.sendMessage(
                        Component.text(
                                "처리 중 오류가 발생했습니다. 관리자에게 문의하세요.",
                                NamedTextColor.RED
                        )
                );
            } finally {
                menu.actionQueued = false;
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private void openCurrentMatchScreen(
            Player player,
            Match match
    ) {
        switch (match.phase()) {
            case SELECTING -> openAgent(player);
            case BUY -> {
                Fighter fighter = match.fighter(player.getUniqueId());

                if (fighter != null && fighter.alive()) {
                    openShop(player);
                } else {
                    openSpectate(player, 0);
                }
            }
            case COMBAT -> {
                Fighter fighter = match.fighter(player.getUniqueId());

                if (fighter != null && !fighter.alive()) {
                    openSpectate(player, 0);
                } else {
                    player.sendMessage(
                            Component.text(
                                    "전투 중입니다. 구매와 방 설정은 사용할 수 없습니다.",
                                    NamedTextColor.RED
                            )
                    );
                }
            }
            case RESULT -> openResult(player);
            default -> player.sendMessage(
                    Component.text(
                            "현재 단계: " + match.phase().korean()
                                    + " · 보스바 안내를 확인하세요.",
                            NamedTextColor.GRAY
                    )
            );
        }
    }

    private Room requireRoom(Player player) {
        Room room = rooms.of(player);

        if (room == null) {
            throw new IllegalArgumentException(
                    "참가 중인 방이 없습니다."
            );
        }

        return room;
    }

    private Room requireLeader(Player player) {
        Room room = requireRoom(player);

        if (!room.isLeader(player.getUniqueId())) {
            throw new IllegalArgumentException(
                    "방장만 사용할 수 있습니다."
            );
        }

        return room;
    }

    private Room requireWaitingLeader(Player player) {
        Room room = requireLeader(player);

        if (room.state() != Room.State.WAITING) {
            throw new IllegalArgumentException(
                    "대기 중인 방에서만 사용할 수 있습니다."
            );
        }

        return room;
    }

    private Room requireSameRoom(Player player, UUID expectedId) {
        Room room = requireRoom(player);

        if (!room.id().equals(expectedId)) {
            throw new IllegalArgumentException(
                    "이 화면을 열었을 때의 방에 더 이상 참가하고 있지 않습니다."
            );
        }

        return room;
    }

    private Match requireMatch(Player player) {
        Match match = matches.of(player);

        if (match == null
                || !match.isPlaying(player.getUniqueId())
                || match.fighter(player.getUniqueId()) == null) {
            throw new IllegalArgumentException(
                    "참가 중인 경기가 없습니다."
            );
        }

        return match;
    }

    private Match requireSameMatch(
            Player player,
            UUID expectedId
    ) {
        Match match = requireMatch(player);

        if (!match.id().equals(expectedId)) {
            throw new IllegalArgumentException(
                    "이 화면의 경기가 더 이상 유효하지 않습니다."
            );
        }

        return match;
    }

    private void requireAdmin(Player player) {
        if (!player.hasPermission("valo.admin")) {
            throw new IllegalArgumentException(
                    "관리자 권한이 필요합니다."
            );
        }
    }

    private ArenaMap findMap(String mapId) {
        if (mapId == null) {
            return null;
        }

        return maps.all().stream()
                .filter(map -> map.id().equals(mapId))
                .findFirst()
                .orElse(null);
    }

    private Menu menu(
            Player player,
            int size,
            String title
    ) {
        Menu menu = new Menu(player.getUniqueId());

        menu.inventory = Bukkit.createInventory(
                menu,
                size,
                Component.text(title, NamedTextColor.DARK_GRAY)
        );

        return menu;
    }

    private void show(Player player, Menu menu) {
        player.openInventory(menu.inventory);
    }

    private void button(
            Menu menu,
            int slot,
            ItemStack item,
            Runnable action
    ) {
        menu.inventory.setItem(slot, item);
        menu.actions.put(slot, action);
    }

    private void decoration(
            Menu menu,
            int slot,
            ItemStack item
    ) {
        menu.inventory.setItem(slot, item);
    }

    private void pagination(
            Menu menu,
            int page,
            int total,
            IntConsumer openPage,
            Runnable back
    ) {
        if (page > 0) {
            button(menu, 45, item(
                    Material.ARROW,
                    "이전 페이지",
                    NamedTextColor.AQUA
            ), () -> openPage.accept(page - 1));
        }

        button(menu, 48, backItem(), back);

        button(menu, 49, item(
                Material.SUNFLOWER,
                "새로고침",
                NamedTextColor.AQUA,
                "현재 목록을 다시 조회합니다."
        ), () -> openPage.accept(page));

        if (page + 1 < pageCount(total)) {
            button(menu, 53, item(
                    Material.ARROW,
                    "다음 페이지",
                    NamedTextColor.AQUA
            ), () -> openPage.accept(page + 1));
        }
    }

    private static int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static int clampPage(int requested, int total) {
        return Math.max(
                0,
                Math.min(requested, pageCount(total) - 1)
        );
    }

    private static ItemStack backItem() {
        return item(
                Material.ARROW,
                "뒤로",
                NamedTextColor.GRAY
        );
    }

    private static ItemStack closeItem() {
        return item(
                Material.OAK_DOOR,
                "닫기",
                NamedTextColor.GRAY
        );
    }

    private static ItemStack item(
            Material material,
            String name,
            NamedTextColor color,
            String... loreLines
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(
                Component.text(name, color)
                        .decoration(
                                net.kyori.adventure.text.format
                                        .TextDecoration.ITALIC,
                                false
                        )
        );

        List<Component> lore = new ArrayList<>();

        for (String line : loreLines) {
            lore.add(
                    Component.text(line, NamedTextColor.GRAY)
                            .decoration(
                                    net.kyori.adventure.text.format
                                            .TextDecoration.ITALIC,
                                    false
                            )
            );
        }

        meta.lore(lore);
        item.setItemMeta(meta);

        return item;
    }

    private static final class Menu implements InventoryHolder {

        private final UUID viewerId;
        private final Map<Integer, Runnable> actions = new HashMap<>();

        private Inventory inventory;
        private boolean actionQueued;

        private Menu(UUID viewerId) {
            this.viewerId = viewerId;
        }

        @Override
        public Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException(
                        "GUI 인벤토리가 아직 초기화되지 않았습니다."
                );
            }

            return inventory;
        }
    }
}