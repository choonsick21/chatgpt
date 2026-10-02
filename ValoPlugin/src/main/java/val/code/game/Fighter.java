package val.code.game;

import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;

public final class Fighter {

    public enum Agent {
        JETT
    }

    private final UUID id;
    private final String name;
    private final Team team;

    private boolean connected = true;
    private boolean alive;

    private Agent selectedAgent;
    private boolean agentConfirmed;

    private int kills;
    private int deaths;
    private int money;
    private int lossStreak;

    private int magazine;
    private int reserveAmmo;
    private long nextFireTick;
    private long reloadFinishTick = -1;

    private int dashCharges;
    private int smokeCharges;
    private long nextDashTick;
    private long nextSmokeTick;

    private double armor;
    private boolean armorPurchased;
    private boolean dashPurchased;
    private boolean smokePurchased;

    private int lastWeaponSlot;
    private long lastSlotActionTick = Long.MIN_VALUE;

    private UUID lastDamager;
    private long lastDamageTick = Long.MIN_VALUE;

    private UUID spectatorTarget;
    private Location roundSpawn;

    public Fighter(
            UUID id,
            String name,
            Team team,
            int startingMoney,
            int maximumMoney
    ) {
        this.id = Objects.requireNonNull(id, "플레이어 UUID");
        this.name = Objects.requireNonNull(name, "플레이어 이름");
        this.team = Objects.requireNonNull(team, "팀");
        setMoney(startingMoney, maximumMoney);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Team team() {
        return team;
    }

    public boolean connected() {
        return connected;
    }

    public void disconnect() {
        connected = false;
        alive = false;
        cancelReload();
        spectatorTarget = null;
    }

    public boolean alive() {
        return alive && connected;
    }

    public Agent selectedAgent() {
        return selectedAgent;
    }

    public boolean agentConfirmed() {
        return agentConfirmed;
    }

    public void selectJett() {
        if (agentConfirmed) {
            throw new IllegalStateException(
                    "이미 요원 선택을 확정했습니다."
            );
        }
        selectedAgent = Agent.JETT;
    }

    public void confirmAgent() {
        if (selectedAgent == null) {
            throw new IllegalArgumentException(
                    "먼저 제트 아이콘을 선택하세요."
            );
        }
        agentConfirmed = true;
    }

    public void autoConfirmJett() {
        selectedAgent = Agent.JETT;
        agentConfirmed = true;
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    public int money() {
        return money;
    }

    public int lossStreak() {
        return lossStreak;
    }

    public void addKill() {
        kills++;
    }

    /**
     * 이미 사망 처리했다면 false를 반환하여 중복 데스를 막습니다.
     */
    public boolean die() {
        if (!alive) {
            return false;
        }

        alive = false;
        deaths++;
        cancelReload();
        spectatorTarget = null;
        return true;
    }

    public void setMoney(int value, int maximum) {
        money = Math.max(0, Math.min(Math.max(0, maximum), value));
    }

    public void addMoney(int amount, int maximum) {
        long result = (long) money + Math.max(0, amount);
        money = (int) Math.min(Math.max(0, maximum), result);
    }

    public boolean spend(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException(
                    "구매 가격은 음수일 수 없습니다."
            );
        }

        if (money < amount) {
            return false;
        }

        money -= amount;
        return true;
    }

    public void recordRoundResult(boolean won) {
        lossStreak = won ? 0 : Math.min(1000, lossStreak + 1);
    }

    public void resetLossStreak() {
        lossStreak = 0;
    }

    public int magazine() {
        return magazine;
    }

    public int reserveAmmo() {
        return reserveAmmo;
    }

    public long nextFireTick() {
        return nextFireTick;
    }

    public boolean reloading() {
        return reloadFinishTick >= 0;
    }

    public long reloadFinishTick() {
        return reloadFinishTick;
    }

    public boolean fire(long tick, int intervalTicks) {
        if (!alive()
                || reloading()
                || magazine <= 0
                || tick < nextFireTick) {
            return false;
        }

        magazine--;
        nextFireTick = tick + Math.max(1, intervalTicks);
        return true;
    }

    public boolean beginReload(
            long tick,
            int durationTicks,
            int magazineCapacity
    ) {
        if (!alive()
                || reloading()
                || reserveAmmo <= 0
                || magazine >= magazineCapacity) {
            return false;
        }

        reloadFinishTick = tick + Math.max(1, durationTicks);
        return true;
    }

    public boolean finishReload(long tick, int magazineCapacity) {
        if (!reloading() || tick < reloadFinishTick) {
            return false;
        }

        reloadFinishTick = -1;

        if (!alive()) {
            return false;
        }

        int needed = Math.max(0, magazineCapacity - magazine);
        int loaded = Math.min(needed, reserveAmmo);

        magazine += loaded;
        reserveAmmo -= loaded;

        return loaded > 0;
    }

    public void cancelReload() {
        reloadFinishTick = -1;
    }

    public int dashCharges() {
        return dashCharges;
    }

    public int smokeCharges() {
        return smokeCharges;
    }

    public boolean dashAvailable(long tick) {
        return alive() && dashCharges > 0 && tick >= nextDashTick;
    }

    public boolean smokeAvailable(long tick) {
        return alive() && smokeCharges > 0 && tick >= nextSmokeTick;
    }

    public boolean consumeDash(long tick, int cooldownTicks) {
        if (!dashAvailable(tick)) {
            return false;
        }

        dashCharges--;
        nextDashTick = tick + Math.max(1, cooldownTicks);
        return true;
    }

    public boolean consumeSmoke(long tick, int cooldownTicks) {
        if (!smokeAvailable(tick)) {
            return false;
        }

        smokeCharges--;
        nextSmokeTick = tick + Math.max(1, cooldownTicks);
        return true;
    }

    public long dashCooldownRemaining(long tick) {
        return Math.max(0, nextDashTick - tick);
    }

    public long smokeCooldownRemaining(long tick) {
        return Math.max(0, nextSmokeTick - tick);
    }

    public double armor() {
        return armor;
    }

    public boolean armorPurchased() {
        return armorPurchased;
    }

    public boolean dashPurchased() {
        return dashPurchased;
    }

    public boolean smokePurchased() {
        return smokePurchased;
    }

    public boolean buyArmor(int price, double points) {
        if (!alive()
                || armorPurchased
                || !Double.isFinite(points)
                || points <= 0) {
            return false;
        }

        if (!spend(price)) {
            return false;
        }

        armorPurchased = true;
        armor = points;
        return true;
    }

    public boolean buyDash(int price, int maximumCharges) {
        if (!alive()
                || dashPurchased
                || dashCharges >= maximumCharges) {
            return false;
        }

        if (!spend(price)) {
            return false;
        }

        dashPurchased = true;
        dashCharges++;
        return true;
    }

    public boolean buySmoke(int price, int maximumCharges) {
        if (!alive()
                || smokePurchased
                || smokeCharges >= maximumCharges) {
            return false;
        }

        if (!spend(price)) {
            return false;
        }

        smokePurchased = true;
        smokeCharges++;
        return true;
    }

    /**
     * 플러그인 자체 방어구 정책:
     * 방어구가 남아 있는 동안 피해의 50%를 방어구가 흡수합니다.
     * 반환값은 체력에 적용할 피해량입니다.
     */
    public double absorbDamage(double incoming) {
        if (!Double.isFinite(incoming) || incoming <= 0) {
            return 0;
        }

        double absorbed = Math.min(armor, incoming * 0.5);
        armor -= absorbed;
        return incoming - absorbed;
    }

    public int lastWeaponSlot() {
        return lastWeaponSlot;
    }

    public void lastWeaponSlot(int slot) {
        if (slot >= 0 && slot <= 3) {
            lastWeaponSlot = slot;
        }
    }

    /**
     * 동일 틱에서 슬롯 이벤트가 여러 번 들어와도 한 번만 처리합니다.
     */
    public boolean claimSlotAction(long tick) {
        if (lastSlotActionTick == tick) {
            return false;
        }

        lastSlotActionTick = tick;
        return true;
    }

    public void damageCredit(UUID attacker, long tick) {
        lastDamager = attacker;
        lastDamageTick = tick;
    }

    public UUID recentDamager(long tick, long maximumAgeTicks) {
        if (lastDamager == null
                || lastDamageTick == Long.MIN_VALUE
                || tick - lastDamageTick > maximumAgeTicks) {
            return null;
        }

        return lastDamager;
    }

    public UUID spectatorTarget() {
        return spectatorTarget;
    }

    public void spectatorTarget(UUID target) {
        spectatorTarget = target;
    }

    public Location roundSpawn() {
        return roundSpawn == null ? null : roundSpawn.clone();
    }

    public void roundSpawn(Location spawn) {
        roundSpawn = spawn == null ? null : spawn.clone();
    }

    public void resetRound(
            int magazineCapacity,
            int reserve,
            int initialDash,
            int initialSmoke
    ) {
        if (!connected) {
            alive = false;
            return;
        }

        alive = true;

        magazine = Math.max(1, magazineCapacity);
        reserveAmmo = Math.max(0, reserve);

        nextFireTick = 0;
        reloadFinishTick = -1;

        dashCharges = Math.max(0, initialDash);
        smokeCharges = Math.max(0, initialSmoke);
        nextDashTick = 0;
        nextSmokeTick = 0;

        armor = 0;
        armorPurchased = false;
        dashPurchased = false;
        smokePurchased = false;

        lastWeaponSlot = 0;
        lastSlotActionTick = Long.MIN_VALUE;

        lastDamager = null;
        lastDamageTick = Long.MIN_VALUE;

        spectatorTarget = null;
    }
}