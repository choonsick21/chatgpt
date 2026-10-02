package val.code.game;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;
import val.code.combat.ClassicService;
import val.code.combat.JettService;
import val.code.combat.SpikeService;
import val.code.config.Settings;
import val.code.item.GameItems;
import val.code.map.ArenaMap;
import val.code.party.Room;
import val.code.sound.SoundService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Match {

    private final MatchService service;
    private final UUID id;
    private final UUID roomId;
    private final ArenaMap map;

    private final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
    private final Set<UUID> capturedPlayers = new HashSet<>();
    private final Set<UUID> restoredPlayers = new HashSet<>();
    private final Map<UUID, Scoreboard> boards = new LinkedHashMap<>();

    private final List<ChunkPosition> preparationChunks = new ArrayList<>();
    private int nextPreparationChunk;

    private final ClassicService classic;
    private final JettService jett;
    private final SpikeService spike;

    private final BossBar bossBar = BossBar.bossBar(
            Component.text("맵 준비 중", NamedTextColor.WHITE),
            0,
            BossBar.Color.RED,
            BossBar.Overlay.PROGRESS
    );

    private final int loadingTicks;
    private final int selectionTicks;
    private final int countdownTicks;
    private final int buyTicks;
    private final int combatTicks;
    private final int roundEndTicks;
    private final int resultTicks;

    private final int targetWins;
    private final int swapAfterRounds;
    private final int magazineCapacity;
    private final int reserveAmmo;
    private final int initialDash;
    private final int initialSmoke;

    private final int startingMoney;
    private final int maximumMoney;
    private final int winReward;
    private final int lossBaseReward;
    private final int lossStep;
    private final int lossCap;
    private final int killReward;

    private final boolean autoSelect;
    private final boolean resetMoneyOnSwap;
    private final boolean restrictBuyMovement;
    private final double buyRadiusSquared;

    private MatchPhase phase = MatchPhase.PREPARING;
    private Team attackingTeam = Team.RED;
    private Team winner;
    private String resultReason = "";

    private long tickNumber;
    private long phaseStartedAt;
    private long phaseDeadline;

    private int redScore;
    private int cyanScore;
    private int completedRounds;
    private int roundNumber;

    private boolean swapped;
    private boolean roundResolved;
    private boolean initialized;

    public Match(
            MatchService service,
            UUID id,
            Room room,
            ArenaMap map
    ) {
        this.service = service;
        this.id = id;
        this.roomId = room.id();
        this.map = map;

        Settings settings = settings();

        loadingTicks = settings.ticks("selection.loading-seconds", 3);
        selectionTicks = settings.ticks("selection.timeout-seconds", 30);
        countdownTicks = settings.ticks("selection.countdown-seconds", 5);
        buyTicks = settings.ticks("match.buy-seconds", 25);
        combatTicks = settings.ticks("match.round-seconds", 100);
        roundEndTicks = settings.ticks("match.round-end-seconds", 5);
        resultTicks = settings.ticks("match.result-seconds", 12);

        targetWins = settings.integer("match.target-wins", 7, 1, 100);
        swapAfterRounds = settings.integer(
                "match.swap-after-rounds", 6, 1, 199
        );

        magazineCapacity = settings.integer(
                "classic.magazine", 12, 1, 100
        );
        reserveAmmo = settings.integer(
                "classic.reserve-ammo", 36, 0, 1000
        );

        int maximumDash = settings.integer(
                "jett.dash.maximum-charges", 2, 1, 10
        );
        int maximumSmoke = settings.integer(
                "jett.smoke.maximum-charges", 3, 1, 10
        );

        initialDash = settings.integer(
                "jett.dash.initial-charges", 1, 0, maximumDash
        );
        initialSmoke = settings.integer(
                "jett.smoke.initial-charges", 2, 0, maximumSmoke
        );

        maximumMoney = settings.integer(
                "economy.maximum-money", 9000, 0, 1_000_000
        );
        startingMoney = settings.integer(
                "economy.starting-money", 800, 0, maximumMoney
        );
        winReward = settings.integer(
                "economy.win-reward", 3000, 0, 1_000_000
        );
        lossBaseReward = settings.integer(
                "economy.loss-base-reward", 1900, 0, 1_000_000
        );
        lossStep = settings.integer(
                "economy.loss-streak-step", 500, 0, 1_000_000
        );
        lossCap = settings.integer(
                "economy.loss-streak-cap", 4, 1, 100
        );
        killReward = settings.integer(
                "economy.kill-reward", 200, 0, 1_000_000
        );

        autoSelect = settings.bool("selection.auto-select", true);
        resetMoneyOnSwap = settings.bool(
                "match.reset-money-on-swap", true
        );
        restrictBuyMovement = settings.bool(
                "match.restrict-buy-movement", true
        );

        double buyRadius = settings.decimal(
                "match.spawn-protection-radius", 7, 1, 30
        );
        buyRadiusSquared = buyRadius * buyRadius;

        List<UUID> shuffled = new ArrayList<>(room.memberIds());
        Collections.shuffle(shuffled);

        // 홀수 인원은 최초 공격 역할 팀인 RED에 한 명 더 배정합니다.
        int redCount = (shuffled.size() + 1) / 2;

        for (int index = 0; index < shuffled.size(); index++) {
            UUID playerId = shuffled.get(index);
            Team team = index < redCount ? Team.RED : Team.CYAN;

            fighters.put(
                    playerId,
                    new Fighter(
                            playerId,
                            room.memberName(playerId),
                            team,
                            startingMoney,
                            maximumMoney
                    )
            );
        }

        classic = new ClassicService(this);
        jett = new JettService(this);
        spike = new SpikeService(this);

        buildPreparationQueue();
    }

    public UUID id() {
        return id;
    }

    public UUID roomId() {
        return roomId;
    }

    public Settings settings() {
        return service.settings();
    }

    public GameItems items() {
        return service.items();
    }

    public SoundService sounds() {
        return service.sounds();
    }

    public ArenaMap map() {
        return map;
    }

    public MatchPhase phase() {
        return phase;
    }

    public long tickNumber() {
        return tickNumber;
    }

    public Team attackingTeam() {
        return attackingTeam;
    }

    public Team winner() {
        return winner;
    }

    public String resultReason() {
        return resultReason;
    }

    public int roundNumber() {
        return roundNumber;
    }

    public int completedRounds() {
        return completedRounds;
    }

    public int score(Team team) {
        return team == Team.RED ? redScore : cyanScore;
    }

    public int remainingSeconds() {
        return (int) Math.max(
                0,
                (phaseDeadline - tickNumber + 19) / 20
        );
    }

    public Collection<Fighter> fighters() {
        return List.copyOf(fighters.values());
    }

    public Fighter fighter(UUID playerId) {
        return fighters.get(playerId);
    }

    public Player player(UUID playerId) {
        return Bukkit.getPlayer(playerId);
    }

    public ClassicService classic() {
        return classic;
    }

    public JettService jett() {
        return jett;
    }

    public SpikeService spike() {
        return spike;
    }

    public boolean restored(UUID playerId) {
        return restoredPlayers.contains(playerId);
    }

    public boolean isPlaying(UUID playerId) {
        Fighter fighter = fighter(playerId);
        return fighter != null
                && fighter.connected()
                && !restoredPlayers.contains(playerId)
                && phase != MatchPhase.CLOSED;
    }

    /**
     * 먼저 모든 원본 상태를 디스크에 저장합니다.
     * 저장 성공 전에는 인벤토리와 게임모드를 변경하지 않습니다.
     */
    public void initialize() {
        if (initialized) {
            throw new IllegalStateException("이미 초기화한 경기입니다.");
        }

        for (Fighter fighter : fighters.values()) {
            Player player = player(fighter.id());

            if (player == null || !player.isOnline()) {
                throw new IllegalStateException(
                        fighter.name() + "님이 접속 중이 아닙니다."
                );
            }

            service.states().capture(player);
            capturedPlayers.add(fighter.id());
        }

        initialized = true;

        for (Fighter fighter : fighters.values()) {
            Player player = player(fighter.id());

            if (player == null) {
                continue;
            }

            prepareSelectionPlayer(player);
            player.showBossBar(bossBar);

            player.sendMessage(
                    Component.text("[VALO] ", NamedTextColor.RED)
                            .append(Component.text(
                                    fighter.team().korean() + "팀 · "
                                            + fighter.team()
                                            .roleName(attackingTeam),
                                    fighter.team().color()
                            ))
            );
        }

        if (fighters.size() % 2 != 0) {
            announce(
                    "홀수 인원: 첫 공격팀에 한 명을 더 배정했습니다. "
                            + "공수 교대 후에도 팀 구성은 유지됩니다.",
                    NamedTextColor.GRAY
            );
        }

        phaseStartedAt = tickNumber;
        phaseDeadline = tickNumber + 20 * 120;
    }

    /**
     * MatchService의 단일 공용 작업에서 매 틱 호출합니다.
     */
    public void tick() {
        if (phase == MatchPhase.CLOSED || !initialized) {
            return;
        }

        tickNumber++;

        if (connectedCount() == 0) {
            service.complete(this);
            return;
        }

        switch (phase) {
            case PREPARING -> tickPreparing();
            case LOADING -> {
                if (tickNumber >= phaseDeadline) {
                    beginSelection();
                }
            }
            case SELECTING -> tickSelection();
            case COUNTDOWN -> tickCountdown();
            case BUY -> {
                classic.tick();
                if (tickNumber >= phaseDeadline) {
                    beginCombat();
                }
            }
            case COMBAT -> tickCombat();
            case ROUND_END -> {
                if (tickNumber >= phaseDeadline) {
                    if (redScore >= targetWins || cyanScore >= targetWins) {
                        beginResult(
                                redScore >= targetWins
                                        ? Team.RED : Team.CYAN,
                                resultReason
                        );
                    } else {
                        beginRound();
                    }
                }
            }
            case RESULT -> {
                if (tickNumber >= phaseDeadline) {
                    service.complete(this);
                    return;
                }
            }
            case CLOSED -> {
                return;
            }
        }

        if (phase == MatchPhase.CLOSED) {
            return;
        }

        if (tickNumber % 5 == 0) {
            updateBossBar();
            updateActionBars();
            maintainSpectators();
        }

        if (tickNumber % 20 == 0) {
            updateScoreboards();
        }
    }

    public void selectJett(Player player) {
        Fighter fighter = requireSelecting(player);
        fighter.selectJett();

        sounds().play(player, "menu-click");

        service.openScreen(
                player, MatchService.Screen.SELECT
        );
    }

    public void confirmAgent(Player player) {
        Fighter fighter = requireSelecting(player);

        if (fighter.agentConfirmed()) {
            throw new IllegalArgumentException(
                    "이미 요원 선택을 확정했습니다."
            );
        }

        fighter.confirmAgent();
        sounds().play(player, "agent-confirm");

        announce(
                fighter.name() + "님이 제트를 확정했습니다. "
                        + "(" + confirmedCount() + "/"
                        + connectedCount() + ")",
                NamedTextColor.AQUA
        );

        service.openScreen(
                player, MatchService.Screen.SELECT
        );

        if (allConfirmed()) {
            beginCountdown();
        }
    }

    public boolean canBuy(Player player) {
        Fighter fighter = fighter(player.getUniqueId());

        return phase == MatchPhase.BUY
                && fighter != null
                && fighter.alive()
                && map.containsPlayer(player.getLocation())
                && nearOwnSpawn(player, fighter);
    }

    public boolean buyArmor(Player player) {
        requireBuying(player);

        Fighter fighter = fighter(player.getUniqueId());
        int price = settings().integer(
                "economy.armor-price", 400, 0, maximumMoney
        );
        double points = settings().decimal(
                "economy.armor-points", 25, 1, 200
        );

        if (!fighter.buyArmor(price, points)) {
            purchaseError(player, fighter.armorPurchased());
            return false;
        }

        purchased(player, "방어구", price);
        return true;
    }

    public boolean buyDash(Player player) {
        requireBuying(player);

        Fighter fighter = fighter(player.getUniqueId());
        int price = settings().integer(
                "economy.dash-charge-price", 200, 0, maximumMoney
        );
        int maximum = settings().integer(
                "jett.dash.maximum-charges", 2, 1, 10
        );

        if (!fighter.buyDash(price, maximum)) {
            purchaseError(
                    player,
                    fighter.dashPurchased()
                            || fighter.dashCharges() >= maximum
            );
            return false;
        }

        refreshSkillItems(player, fighter);
        purchased(player, "대시 추가 충전", price);
        return true;
    }

    public boolean buySmoke(Player player) {
        requireBuying(player);

        Fighter fighter = fighter(player.getUniqueId());
        int price = settings().integer(
                "economy.smoke-charge-price", 200, 0, maximumMoney
        );
        int maximum = settings().integer(
                "jett.smoke.maximum-charges", 3, 1, 10
        );

        if (!fighter.buySmoke(price, maximum)) {
            purchaseError(
                    player,
                    fighter.smokePurchased()
                            || fighter.smokeCharges() >= maximum
            );
            return false;
        }

        refreshSkillItems(player, fighter);
        purchased(player, "연막 추가 충전", price);
        return true;
    }

    public void damage(
            UUID attackerId,
            UUID victimId,
            double amount,
            boolean headshot
    ) {
        if (phase != MatchPhase.COMBAT
                || roundResolved
                || !Double.isFinite(amount)
                || amount <= 0) {
            return;
        }

        Fighter attacker = fighter(attackerId);
        Fighter victim = fighter(victimId);
        Player target = player(victimId);

        if (attacker == null
                || victim == null
                || !attacker.alive()
                || !victim.alive()
                || target == null
                || !map.containsPlayer(target.getLocation())) {
            return;
        }

        if (attacker.team() == victim.team()
                && !settings().bool("match.friendly-fire", false)) {
            return;
        }

        victim.damageCredit(attackerId, tickNumber);

        double healthDamage = victim.absorbDamage(amount);

        if (healthDamage >= target.getHealth()) {
            kill(victimId, attackerId, headshot ? "헤드샷" : "클래식");
        } else if (healthDamage > 0) {
            target.setHealth(
                    Math.max(0.01, target.getHealth() - healthDamage)
            );
            target.setNoDamageTicks(0);
        }
    }

    /**
     * 이벤트 리스너가 낙하·용암 등의 바닐라 피해를 취소한 뒤 호출합니다.
     * 구매 및 준비 단계에는 환경 피해가 적용되지 않습니다.
     */
    public void environmentDamage(UUID victimId, double amount) {
        if (phase != MatchPhase.COMBAT
                || roundResolved
                || !Double.isFinite(amount)
                || amount <= 0) {
            return;
        }

        Fighter victim = fighter(victimId);
        Player target = player(victimId);

        if (victim == null || !victim.alive() || target == null) {
            return;
        }

        if (amount >= target.getHealth()) {
            kill(
                    victimId,
                    victim.recentDamager(tickNumber, 200),
                    "환경 피해"
            );
        } else {
            target.setHealth(
                    Math.max(0.01, target.getHealth() - amount)
            );
        }
    }

    /**
     * 다른 플러그인이 직접 setHealth(0)를 호출한 경우 등
     * 실제 PlayerDeathEvent가 발생했을 때의 상태 정리입니다.
     */
    public void externalDeath(UUID playerId) {
        Fighter fighter = fighter(playerId);

        if (fighter == null || !fighter.alive()) {
            return;
        }

        Player player = player(playerId);
        Location location = player == null
                ? fighter.roundSpawn() : player.getLocation();

        spike.dropCarrier(playerId, location);
        spike.cancel(playerId);
        jett.removePlayer(playerId);

        fighter.die();

        announce(
                fighter.name() + "님이 사망했습니다.",
                NamedTextColor.GRAY
        );

        evaluateElimination();
    }

    /**
     * 실제 리스폰 이벤트 이후 호출합니다.
     */
    public void afterRespawn(Player player) {
        Fighter fighter = fighter(player.getUniqueId());

        if (fighter == null || restored(player.getUniqueId())) {
            return;
        }

        if (phase == MatchPhase.RESULT) {
            makeSpectator(player);
            service.openScreen(player, MatchService.Screen.RESULT);
        } else if (!fighter.alive()) {
            makeSpectator(player);
        }
    }

    public void chooseSpectatorTarget(Player viewer, UUID targetId) {
        Fighter watcher = fighter(viewer.getUniqueId());
        Fighter target = fighter(targetId);
        Player targetPlayer = player(targetId);

        if (watcher == null
                || watcher.alive()
                || !watcher.connected()
                || target == null
                || !target.alive()
                || targetPlayer == null
                || !phase.isRoundActive()
                || !map.containsPlayer(targetPlayer.getLocation())) {
            throw new IllegalArgumentException(
                    "현재 관전할 수 없는 대상입니다."
            );
        }

        watcher.spectatorTarget(targetId);
        viewer.closeInventory();
        viewer.setGameMode(GameMode.SPECTATOR);

        if (viewer.teleport(targetPlayer.getLocation())) {
            viewer.setSpectatorTarget(targetPlayer);
        } else {
            watcher.spectatorTarget(null);
            moveSpectatorToSafePosition(viewer);
        }
    }

    public boolean movementAllowed(Player player, Location destination) {
        if (destination == null || !isPlaying(player.getUniqueId())) {
            return true;
        }

        if (!isArenaStage()) {
            return true;
        }

        if (!map.containsPlayer(destination)) {
            return false;
        }

        Fighter fighter = fighter(player.getUniqueId());

        if (phase == MatchPhase.BUY
                && restrictBuyMovement
                && fighter != null
                && fighter.alive()) {
            return nearSpawn(destination, fighter.roundSpawn());
        }

        return true;
    }

    public boolean isArenaStage() {
        return phase == MatchPhase.BUY
                || phase == MatchPhase.COMBAT
                || phase == MatchPhase.ROUND_END
                || phase == MatchPhase.RESULT;
    }

    public Location safeLocation(UUID playerId) {
        Fighter fighter = fighter(playerId);

        if (fighter != null && fighter.alive()) {
            Location spawn = fighter.roundSpawn();

            if (spawn != null && map.containsPlayer(spawn)) {
                return spawn;
            }
        }

        return map.spectator();
    }

    /**
     * 라운드 점수와 보상은 이 메서드에서 한 번만 반영합니다.
     */
    public void endRound(Team winningTeam, String reason) {
        if (winningTeam == null
                || roundResolved
                || (phase != MatchPhase.BUY
                && phase != MatchPhase.COMBAT)) {
            return;
        }

        roundResolved = true;

        if (winningTeam == Team.RED) {
            redScore++;
        } else {
            cyanScore++;
        }

        completedRounds++;
        resultReason = reason;

        classic.cancelReloads();
        jett.clear();
        spike.clear();

        for (Fighter fighter : fighters.values()) {
            if (!fighter.connected()) {
                continue;
            }

            boolean won = fighter.team() == winningTeam;
            fighter.recordRoundResult(won);

            int reward;

            if (won) {
                reward = winReward;
            } else {
                int stepCount = Math.min(
                        Math.max(0, fighter.lossStreak() - 1),
                        lossCap - 1
                );

                long calculated = (long) lossBaseReward
                        + (long) stepCount * lossStep;

                reward = (int) Math.min(
                        Integer.MAX_VALUE, calculated
                );
            }

            fighter.addMoney(reward, maximumMoney);

            Player player = player(fighter.id());

            if (player != null) {
                player.closeInventory();
                player.showTitle(Title.title(
                        Component.text(
                                won ? "라운드 승리" : "라운드 패배",
                                won ? NamedTextColor.AQUA
                                        : NamedTextColor.RED
                        ),
                        Component.text(
                                reason + " · " + redScore + " : " + cyanScore,
                                NamedTextColor.GRAY
                        ),
                        shortTimes()
                ));
                sounds().play(player, "round-end");
            }
        }

        announce(
                winningTeam.korean() + "팀 라운드 승리 · "
                        + reason + " · " + redScore + " : " + cyanScore,
                winningTeam.color()
        );

        transition(MatchPhase.ROUND_END, roundEndTicks);
    }

    /**
     * RoomService가 소속을 제거한 뒤 호출합니다.
     */
    public void depart(UUID playerId) {
        Fighter fighter = fighter(playerId);

        if (fighter == null || !fighter.connected()) {
            return;
        }

        Player player = player(playerId);

        Location dropLocation = player == null
                ? fighter.roundSpawn() : player.getLocation();

        spike.dropCarrier(playerId, dropLocation);
        spike.cancel(playerId);
        jett.removePlayer(playerId);

        fighter.disconnect();

        if (player != null) {
            player.hideBossBar(bossBar);
        }

        boards.remove(playerId);
        service.releasePlayer(this, playerId, true);

        if (connectedCount() == 0) {
            service.complete(this);
            return;
        }

        if (phase == MatchPhase.SELECTING && allConfirmed()) {
            beginCountdown();
        }

        if (phase == MatchPhase.BUY || phase == MatchPhase.COMBAT) {
            evaluateElimination();
        }
    }

    /**
     * 결과 화면에서 로비 버튼을 누른 경우입니다.
     * 방 소속은 유지합니다.
     */
    public void returnFromResult(UUID playerId) {
        if (phase != MatchPhase.RESULT) {
            throw new IllegalArgumentException(
                    "경기 결과 단계에서만 사용할 수 있습니다."
            );
        }

        Fighter fighter = fighter(playerId);

        if (fighter == null || !fighter.connected()) {
            return;
        }

        Player player = player(playerId);

        if (player != null) {
            player.hideBossBar(bossBar);
        }

        fighter.disconnect();
        boards.remove(playerId);

        service.releasePlayer(this, playerId, true);

        if (connectedCount() == 0) {
            service.complete(this);
        }
    }

    /**
     * 관리자가 강제 종료하거나 내부 오류가 발생한 경우.
     * 경기 점수·보상은 추가로 지급하지 않습니다.
     */
    public void abort(String reason) {
        if (phase == MatchPhase.CLOSED) {
            return;
        }

        resultReason = reason;

        announce(
                "경기가 중단되었습니다: " + reason,
                NamedTextColor.RED
        );

        service.complete(this);
    }

    /**
     * 서비스가 예약 해제와 방 상태 변경을 하기 전에 호출합니다.
     */
    public void cleanup() {
        if (phase == MatchPhase.CLOSED) {
            return;
        }

        phase = MatchPhase.CLOSED;

        classic.cancelReloads();
        jett.clear();
        spike.clear();

        for (Fighter fighter : fighters.values()) {
            Player player = player(fighter.id());

            if (player != null) {
                player.hideBossBar(bossBar);

                if (player.getGameMode() == GameMode.SPECTATOR) {
                    player.setSpectatorTarget(null);
                }
            }
        }

        boards.clear();
    }

    public boolean captured(UUID playerId) {
        return capturedPlayers.contains(playerId);
    }

    public void markRestored(UUID playerId) {
        restoredPlayers.add(playerId);
    }

    public void announce(String text, NamedTextColor color) {
        Component message = Component.text("[VALO] ", NamedTextColor.RED)
                .append(Component.text(text, color));

        for (Fighter fighter : fighters.values()) {
            if (!fighter.connected() || restored(fighter.id())) {
                continue;
            }

            Player player = player(fighter.id());

            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private void tickPreparing() {
        if (tickNumber >= phaseDeadline) {
            abort("맵 준비 제한 시간을 초과했습니다.");
            return;
        }

        // 한 틱에 최대 두 개의 필수 청크만 준비합니다.
        int budget = 2;

        while (budget-- > 0
                && nextPreparationChunk < preparationChunks.size()) {
            ChunkPosition position = preparationChunks.get(
                    nextPreparationChunk++
            );

            World world = map.world();

            if (world == null) {
                abort("맵 월드가 로드되어 있지 않습니다.");
                return;
            }

            if (!world.getChunkAt(position.x(), position.z()).load()) {
                abort("필수 청크를 로드하지 못했습니다.");
                return;
            }
        }

        if (nextPreparationChunk < preparationChunks.size()) {
            return;
        }

        List<String> problems = map.problems(true);

        if (!problems.isEmpty()) {
            abort("맵 검증 실패: " + String.join(" / ", problems));
            return;
        }

        transition(MatchPhase.LOADING, loadingTicks);

        for (Fighter fighter : fighters.values()) {
            if (!fighter.connected()) {
                continue;
            }

            Player player = player(fighter.id());

            if (player != null) {
                player.showTitle(Title.title(
                        Component.text(
                                map.displayName(), NamedTextColor.WHITE
                        ),
                        Component.text(
                                "맵 준비 완료 · 경기 정보를 불러오는 중",
                                NamedTextColor.GRAY
                        ),
                        shortTimes()
                ));
            }
        }
    }

    private void beginSelection() {
        transition(MatchPhase.SELECTING, selectionTicks);

        announce(
                "제트를 선택한 뒤 확정 버튼을 누르세요. "
                        + "/valo agent로 선택 화면을 다시 열 수 있습니다.",
                NamedTextColor.AQUA
        );

        for (Fighter fighter : fighters.values()) {
            Player player = player(fighter.id());

            if (fighter.connected() && player != null) {
                service.openScreen(
                        player, MatchService.Screen.SELECT
                );
            }
        }
    }

    private void tickSelection() {
        if (allConfirmed()) {
            beginCountdown();
            return;
        }

        if (tickNumber < phaseDeadline) {
            return;
        }

        if (!autoSelect) {
            abort("제한 시간 내에 모든 참가자가 요원을 확정하지 않았습니다.");
            return;
        }

        for (Fighter fighter : fighters.values()) {
            if (fighter.connected() && !fighter.agentConfirmed()) {
                fighter.autoConfirmJett();

                announce(
                        fighter.name() + "님을 제트로 자동 선택했습니다.",
                        NamedTextColor.GRAY
                );
            }
        }

        beginCountdown();
    }

    private void beginCountdown() {
        if (phase != MatchPhase.SELECTING) {
            return;
        }

        transition(MatchPhase.COUNTDOWN, countdownTicks);

        for (Fighter fighter : fighters.values()) {
            Player player = player(fighter.id());

            if (fighter.connected() && player != null) {
                player.closeInventory();
            }
        }
    }

    private void tickCountdown() {
        if (tickNumber >= phaseDeadline) {
            beginRound();
            return;
        }

        if ((tickNumber - phaseStartedAt) % 20 == 0) {
            for (Fighter fighter : fighters.values()) {
                Player player = player(fighter.id());

                if (fighter.connected() && player != null) {
                    player.showTitle(Title.title(
                            Component.text(
                                    String.valueOf(remainingSeconds()),
                                    NamedTextColor.RED
                            ),
                            Component.text(
                                    "경기가 곧 시작됩니다",
                                    NamedTextColor.GRAY
                            ),
                            Title.Times.times(
                                    Duration.ZERO,
                                    Duration.ofMillis(850),
                                    Duration.ofMillis(100)
                            )
                    ));
                    sounds().play(player, "countdown");
                }
            }
        }
    }

    private void beginRound() {
        if (phase != MatchPhase.COUNTDOWN
                && phase != MatchPhase.ROUND_END) {
            return;
        }

        if (connectedOnTeam(Team.RED) == 0
                || connectedOnTeam(Team.CYAN) == 0) {
            beginResult(
                    connectedOnTeam(Team.RED) > 0 ? Team.RED : Team.CYAN,
                    "상대 팀의 모든 참가자가 퇴장했습니다."
            );
            return;
        }

        if (!swapped && completedRounds >= swapAfterRounds) {
            swapped = true;
            attackingTeam = attackingTeam.opposite();

            for (Fighter fighter : fighters.values()) {
                fighter.resetLossStreak();

                if (resetMoneyOnSwap) {
                    fighter.setMoney(startingMoney, maximumMoney);
                }
            }

            announce(
                    "공수가 교대되었습니다."
                            + (resetMoneyOnSwap
                            ? " 자금이 시작 자금으로 초기화됩니다." : ""),
                    NamedTextColor.AQUA
            );
        }

        roundNumber++;
        roundResolved = false;

        jett.clear();
        spike.clear();
        classic.cancelReloads();

        // 스폰 이동 전에 BUY로 전환하여 목적지 경계 검사를 활성화합니다.
        transition(MatchPhase.BUY, buyTicks);

        int attackingIndex = 0;
        int defendingIndex = 0;

        for (Fighter fighter : fighters.values()) {
            if (!fighter.connected() || restored(fighter.id())) {
                continue;
            }

            Player player = player(fighter.id());

            if (player == null || player.isDead()) {
                abort(fighter.name() + "님의 상태를 준비할 수 없습니다.");
                return;
            }

            fighter.resetRound(
                    magazineCapacity,
                    reserveAmmo,
                    initialDash,
                    initialSmoke
            );

            boolean attacking = fighter.team() == attackingTeam;
            int index = attacking ? attackingIndex++ : defendingIndex++;

            Location spawn = map.spawn(attacking, index);
            fighter.roundSpawn(spawn);

            prepareRoundPlayer(player, fighter);

            if (spawn == null || !player.teleport(spawn)) {
                abort(fighter.name() + "님의 팀 스폰 이동에 실패했습니다.");
                return;
            }

            player.showTitle(Title.title(
                    Component.text(
                            "ROUND " + roundNumber,
                            NamedTextColor.WHITE
                    ),
                    Component.text(
                            fighter.team().roleName(attackingTeam)
                                    + " · 구매 단계",
                            fighter.team().color()
                    ),
                    shortTimes()
            ));
        }

        spike.startRound();

        announce(
                "구매 단계입니다. 5번 슬롯으로 상점을 여세요.",
                NamedTextColor.AQUA
        );

        updateScoreboards();
    }

    private void beginCombat() {
        if (phase != MatchPhase.BUY || roundResolved) {
            return;
        }

        transition(MatchPhase.COMBAT, combatTicks);

        for (Fighter fighter : fighters.values()) {
            Player player = player(fighter.id());

            if (fighter.connected() && player != null) {
                player.closeInventory();
                sounds().play(player, "round-start");

                player.showTitle(Title.title(
                        Component.text("전투 시작", NamedTextColor.RED),
                        Component.text(
                                "목표를 수행하세요",
                                NamedTextColor.GRAY
                        ),
                        shortTimes()
                ));
            }
        }

        evaluateElimination();
    }

    private void tickCombat() {
        classic.tick();
        jett.tick();
        spike.tick();

        if (phase != MatchPhase.COMBAT || roundResolved) {
            return;
        }

        evaluateElimination();

        if (phase != MatchPhase.COMBAT || roundResolved) {
            return;
        }

        if (!spike.planted() && tickNumber >= phaseDeadline) {
            endRound(
                    attackingTeam.opposite(),
                    "설치 전 라운드 시간 초과"
            );
        }
    }

    private void kill(
            UUID victimId,
            UUID attackerId,
            String cause
    ) {
        Fighter victim = fighter(victimId);
        Player target = player(victimId);

        if (victim == null || !victim.alive() || target == null) {
            return;
        }

        spike.dropCarrier(victimId, target.getLocation());
        spike.cancel(victimId);
        jett.removePlayer(victimId);

        if (!victim.die()) {
            return;
        }

        Fighter attacker = attackerId == null
                ? null : fighter(attackerId);

        String killerName = cause;

        if (attacker != null
                && !attacker.id().equals(victim.id())
                && attacker.team() != victim.team()) {
            attacker.addKill();
            attacker.addMoney(killReward, maximumMoney);
            killerName = attacker.name();
        }

        announce(
                killerName + " → " + victim.name() + " · " + cause,
                NamedTextColor.GRAY
        );

        makeSpectator(target);
        evaluateElimination();
    }

    private void evaluateElimination() {
        if (roundResolved
                || (phase != MatchPhase.BUY
                && phase != MatchPhase.COMBAT)) {
            return;
        }

        int attackers = aliveOnTeam(attackingTeam);
        int defenders = aliveOnTeam(attackingTeam.opposite());

        if (spike.planted()) {
            // 설치 이후 공격팀 전멸만으로 수비 승리를 부여하지 않습니다.
            if (defenders == 0 && attackers > 0) {
                endRound(attackingTeam, "수비팀 전멸");
            }
            return;
        }

        if (attackers == 0) {
            endRound(attackingTeam.opposite(), "공격팀 전멸");
        } else if (defenders == 0) {
            endRound(attackingTeam, "수비팀 전멸");
        }
    }

    private void beginResult(Team winningTeam, String reason) {
        if (phase == MatchPhase.RESULT || phase == MatchPhase.CLOSED) {
            return;
        }

        winner = winningTeam;
        resultReason = reason;

        if (phase == MatchPhase.COUNTDOWN) {
            // 선택 후 상대 팀이 전원 이탈한 예외 상황.
            phase = MatchPhase.RESULT;
            phaseStartedAt = tickNumber;
            phaseDeadline = tickNumber + resultTicks;
        } else {
            transition(MatchPhase.RESULT, resultTicks);
        }

        classic.cancelReloads();
        jett.clear();
        spike.clear();

        for (Fighter fighter : fighters.values()) {
            Player player = player(fighter.id());

            if (!fighter.connected() || player == null) {
                continue;
            }

            boolean won = fighter.team() == winner;

            makeSpectator(player);

            player.showTitle(Title.title(
                    Component.text(
                            won ? "VICTORY" : "DEFEAT",
                            won ? NamedTextColor.AQUA
                                    : NamedTextColor.RED
                    ),
                    Component.text(
                            (won ? "승리" : "패배")
                                    + " · " + redScore + " : " + cyanScore,
                            NamedTextColor.WHITE
                    ),
                    Title.Times.times(
                            Duration.ofMillis(300),
                            Duration.ofSeconds(4),
                            Duration.ofMillis(700)
                    )
            ));

            sounds().play(player, won ? "victory" : "defeat");
            service.openScreen(player, MatchService.Screen.RESULT);
        }

        announce(
                "경기가 종료되었습니다. 잠시 후 로비로 복귀합니다. "
                        + "파티는 유지됩니다.",
                NamedTextColor.GRAY
        );
    }

    private void prepareSelectionPlayer(Player player) {
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().clear();

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }

        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        player.setCollidable(false);
        player.setGravity(true);
        player.setVelocity(new Vector());
        player.setFallDistance(0);
        player.setSprinting(false);
        player.setSneaking(false);

        clearEffects(player);
        normalizeVitals(player);
    }

    private void prepareRoundPlayer(Player player, Fighter fighter) {
        player.closeInventory();
        player.setItemOnCursor(null);

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }

        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        player.setCollidable(false);
        player.setGravity(true);
        player.setVelocity(new Vector());
        player.setFallDistance(0);

        clearEffects(player);
        normalizeVitals(player);

        player.getInventory().clear();
        player.getInventory().setItem(0, items().classic(id));
        player.getInventory().setItem(4, items().shop(id));
        refreshSkillItems(player, fighter);
        player.getInventory().setHeldItemSlot(0);
    }

    private void makeSpectator(Player player) {
        if (player.isDead()) {
            return;
        }

        Fighter fighter = fighter(player.getUniqueId());

        if (fighter != null) {
            fighter.spectatorTarget(null);
        }

        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().clear();
        player.getInventory().setItem(0, items().spectate(id));
        player.getInventory().setHeldItemSlot(0);

        clearEffects(player);
        normalizeVitals(player);

        player.setGameMode(GameMode.SPECTATOR);
        player.setCollidable(false);
        player.setFallDistance(0);

        moveSpectatorToSafePosition(player);
    }

    private void maintainSpectators() {
        if (!isArenaStage()) {
            return;
        }

        for (Fighter fighter : fighters.values()) {
            if (!fighter.connected() || restored(fighter.id())) {
                continue;
            }

            Player player = player(fighter.id());

            if (player == null || player.isDead()) {
                continue;
            }

            boolean spectator = !fighter.alive()
                    || phase == MatchPhase.RESULT;

            if (!spectator) {
                if (!map.containsPlayer(player.getLocation())) {
                    Location safe = safeLocation(fighter.id());

                    if (safe != null) {
                        player.teleport(safe);
                        player.setFallDistance(0);
                    }
                }
                continue;
            }

            UUID targetId = fighter.spectatorTarget();

            if (targetId != null) {
                Fighter target = fighter(targetId);
                Player targetPlayer = player(targetId);

                if (target == null
                        || !target.alive()
                        || targetPlayer == null
                        || !map.containsPlayer(targetPlayer.getLocation())
                        || phase == MatchPhase.RESULT) {
                    fighter.spectatorTarget(null);
                    moveSpectatorToSafePosition(player);
                    continue;
                }
            }

            if (!map.containsPlayer(player.getLocation())) {
                fighter.spectatorTarget(null);
                moveSpectatorToSafePosition(player);
            }
        }
    }

    private void moveSpectatorToSafePosition(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }

        Location spectator = map.spectator();

        if (spectator != null) {
            player.teleport(spectator);
            player.setVelocity(new Vector());
            player.setFallDistance(0);
        }
    }

    private void normalizeVitals(Player player) {
        AttributeInstance maximum = player.getAttribute(
                Attribute.MAX_HEALTH
        );

        double maximumHealth = maximum == null
                ? 20 : maximum.getValue();

        player.setHealth(Math.max(
                0.01, Math.min(20, maximumHealth)
        ));
        player.setAbsorptionAmount(0);
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setNoDamageTicks(0);
        player.setExp(0);
        player.setLevel(0);
    }

    private void clearEffects(Player player) {
        for (PotionEffect effect :
                new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
    }

    private void refreshSkillItems(Player player, Fighter fighter) {
        player.getInventory().setItem(
                5, items().dash(id, fighter.dashCharges())
        );
        player.getInventory().setItem(
                6, items().smoke(id, fighter.smokeCharges())
        );
    }

    private Fighter requireSelecting(Player player) {
        Fighter fighter = fighter(player.getUniqueId());

        if (phase != MatchPhase.SELECTING
                || fighter == null
                || !fighter.connected()) {
            throw new IllegalArgumentException(
                    "현재 요원 선택 단계가 아닙니다."
            );
        }

        return fighter;
    }

    private void requireBuying(Player player) {
        if (!canBuy(player)) {
            throw new IllegalArgumentException(
                    "구매 단계에서 자신의 팀 스폰 근처에서만 구매할 수 있습니다."
            );
        }
    }

    private void purchased(Player player, String name, int price) {
        player.sendMessage(
                Component.text(
                        name + " 구매 완료 · " + price + " 크레딧",
                        NamedTextColor.AQUA
                )
        );
        sounds().play(player, "menu-click");
        service.openScreen(player, MatchService.Screen.SHOP);
    }

    private void purchaseError(Player player, boolean duplicate) {
        player.sendMessage(
                Component.text(
                        duplicate
                                ? "이미 구매했거나 최대 충전 상태입니다."
                                : "잔액이 부족합니다.",
                        NamedTextColor.RED
                )
        );
        sounds().play(player, "menu-error");
    }

    private boolean nearOwnSpawn(Player player, Fighter fighter) {
        return nearSpawn(player.getLocation(), fighter.roundSpawn());
    }

    private boolean nearSpawn(Location location, Location spawn) {
        if (location == null
                || spawn == null
                || !location.getWorld().equals(spawn.getWorld())) {
            return false;
        }

        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();

        return dx * dx + dz * dz <= buyRadiusSquared;
    }

    private int connectedCount() {
        return (int) fighters.values().stream()
                .filter(Fighter::connected)
                .count();
    }

    private int connectedOnTeam(Team team) {
        return (int) fighters.values().stream()
                .filter(Fighter::connected)
                .filter(fighter -> fighter.team() == team)
                .count();
    }

    private int aliveOnTeam(Team team) {
        return (int) fighters.values().stream()
                .filter(Fighter::alive)
                .filter(fighter -> fighter.team() == team)
                .count();
    }

    private int confirmedCount() {
        return (int) fighters.values().stream()
                .filter(Fighter::connected)
                .filter(Fighter::agentConfirmed)
                .count();
    }

    private boolean allConfirmed() {
        return connectedCount() > 0
                && confirmedCount() == connectedCount();
    }

    private void transition(MatchPhase next, int durationTicks) {
        if (!phase.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "허용하지 않는 경기 상태 전환: "
                            + phase + " -> " + next
            );
        }

        phase = next;
        phaseStartedAt = tickNumber;
        phaseDeadline = tickNumber + Math.max(1, durationTicks);
    }

    private void updateBossBar() {
        String text;
        float progress;

        if (phase == MatchPhase.PREPARING) {
            text = "맵 준비 · " + map.displayName();

            progress = preparationChunks.isEmpty()
                    ? 1
                    : (float) nextPreparationChunk
                    / preparationChunks.size();
        } else if (phase == MatchPhase.COMBAT && spike.planted()) {
            long remaining = spike.remainingExplosionTicks();

            text = "스파이크 설치됨 · "
                    + ((remaining + 19) / 20) + "초";

            int total = settings().ticks(
                    "spike.explosion-seconds", 40
            );

            progress = (float) remaining / total;
        } else {
            text = phase.korean() + " · " + remainingSeconds() + "초"
                    + " · " + redScore + " : " + cyanScore;

            if (phase == MatchPhase.SELECTING) {
                text += " · 확정 " + confirmedCount()
                        + "/" + connectedCount();
            }

            long total = Math.max(1, phaseDeadline - phaseStartedAt);
            progress = (float) Math.max(
                    0, phaseDeadline - tickNumber
            ) / total;
        }

        bossBar.name(Component.text(text, NamedTextColor.WHITE));
        bossBar.progress(Math.max(0, Math.min(1, progress)));

        bossBar.color(
                phase == MatchPhase.BUY
                        ? BossBar.Color.BLUE : BossBar.Color.RED
        );
    }

    private void updateActionBars() {
        for (Fighter fighter : fighters.values()) {
            if (!fighter.connected() || restored(fighter.id())) {
                continue;
            }

            Player player = player(fighter.id());

            if (player == null) {
                continue;
            }

            if (spike.hasAction(fighter.id())) {
                continue;
            }

            if (phase.isRoundActive() && fighter.alive()) {
                String ammo = fighter.reloading()
                        ? "재장전 중"
                        : fighter.magazine() + "/" + fighter.reserveAmmo();

                player.sendActionBar(
                        Component.text("클래식 " + ammo, NamedTextColor.WHITE)
                                .append(Component.text(
                                        "  │  자금 " + fighter.money(),
                                        NamedTextColor.AQUA
                                ))
                                .append(Component.text(
                                        "  │  방어구 "
                                                + (int) Math.ceil(
                                                fighter.armor()
                                        ),
                                        NamedTextColor.GRAY
                                ))
                );
            } else if (isArenaStage()) {
                player.sendActionBar(
                        Component.text(
                                "관전 중 · /valo spectate",
                                NamedTextColor.GRAY
                        )
                );
            }
        }
    }

    private void updateScoreboards() {
        for (Fighter fighter : fighters.values()) {
            if (!fighter.connected() || restored(fighter.id())) {
                continue;
            }

            Player player = player(fighter.id());

            if (player == null) {
                continue;
            }

            Scoreboard board = boards.computeIfAbsent(
                    fighter.id(),
                    ignored -> Bukkit.getScoreboardManager()
                            .getNewScoreboard()
            );

            for (String entry : new HashSet<>(board.getEntries())) {
                board.resetScores(entry);
            }

            Objective objective = board.getObjective("valo");

            if (objective == null) {
                objective = board.registerNewObjective(
                        "valo",
                        "dummy",
                        Component.text("VALO", NamedTextColor.RED)
                );
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            }

            objective.getScore(
                    "§f" + map.displayName()
            ).setScore(9);

            objective.getScore(
                    "§7" + phase.korean()
            ).setScore(8);

            objective.getScore(
                    "§c레드 §f" + redScore
                            + " §8: §f" + cyanScore + " §b청록"
            ).setScore(7);

            objective.getScore(
                    "§8────────────"
            ).setScore(6);

            objective.getScore(
                    "§f팀: "
                            + (fighter.team() == Team.RED ? "§c" : "§b")
                            + fighter.team().korean()
            ).setScore(5);

            objective.getScore(
                    "§7역할: §f"
                            + fighter.team().roleName(attackingTeam)
            ).setScore(4);

            objective.getScore(
                    "§b자금: §f" + fighter.money()
            ).setScore(3);

            objective.getScore(
                    "§fK/D: §7" + fighter.kills()
                            + "/" + fighter.deaths()
            ).setScore(2);

            objective.getScore(
                    "§7라운드: §f" + roundNumber
            ).setScore(1);

            player.setScoreboard(board);
        }
    }

    private void buildPreparationQueue() {
        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        List<Location> locations = new ArrayList<>();

        locations.addAll(map.attackSpawns());
        locations.addAll(map.defenseSpawns());

        locations.add(map.spectator());
        locations.add(map.siteA1());
        locations.add(map.siteA2());
        locations.add(map.siteB1());
        locations.add(map.siteB2());

        for (Location location : locations) {
            if (location == null) {
                continue;
            }

            int centerX = location.getBlockX() >> 4;
            int centerZ = location.getBlockZ() >> 4;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    chunks.add(new ChunkPosition(
                            centerX + dx,
                            centerZ + dz
                    ));
                }
            }
        }

        preparationChunks.addAll(chunks);
    }

    private static Title.Times shortTimes() {
        return Title.Times.times(
                Duration.ofMillis(150),
                Duration.ofSeconds(2),
                Duration.ofMillis(400)
        );
    }

    private record ChunkPosition(int x, int z) {
    }
}