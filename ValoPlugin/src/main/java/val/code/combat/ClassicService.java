package val.code.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import val.code.config.Settings;
import val.code.game.Fighter;
import val.code.game.Match;
import val.code.item.GameItems;

public final class ClassicService {

    private final Match match;

    private final int magazineCapacity;
    private final int fireInterval;
    private final int reloadDuration;

    private final double maximumRange;
    private final double nearDistance;
    private final double middleDistance;
    private final double nearDamage;
    private final double middleDamage;
    private final double farDamage;
    private final double headMultiplier;
    private final double headTolerance;

    private final boolean friendlyFire;

    public ClassicService(Match match) {
        this.match = match;

        Settings settings = match.settings();

        magazineCapacity = settings.integer(
                "classic.magazine", 12, 1, 100
        );
        fireInterval = settings.integer(
                "classic.fire-interval-ticks", 5, 1, 100
        );
        reloadDuration = settings.integer(
                "classic.reload-ticks", 30, 1, 1200
        );

        maximumRange = settings.decimal(
                "classic.maximum-range", 70, 1, 256
        );
        nearDistance = settings.decimal(
                "classic.damage.near-distance", 15, 0, maximumRange
        );
        middleDistance = Math.max(
                nearDistance,
                settings.decimal(
                        "classic.damage.middle-distance",
                        30,
                        0,
                        maximumRange
                )
        );

        nearDamage = settings.decimal(
                "classic.damage.near", 5.2, 0, 100
        );
        middleDamage = settings.decimal(
                "classic.damage.middle", 4.4, 0, 100
        );
        farDamage = settings.decimal(
                "classic.damage.far", 3.6, 0, 100
        );
        headMultiplier = settings.decimal(
                "classic.headshot-multiplier", 3, 1, 10
        );
        headTolerance = settings.decimal(
                "classic.headshot-height-tolerance", 0.30, 0.05, 0.6
        );

        friendlyFire = settings.bool(
                "match.friendly-fire", false
        );
    }

    public boolean fire(Player shooter) {
        Fighter fighter = match.fighter(shooter.getUniqueId());

        if (!canUse(shooter, fighter)) {
            return false;
        }

        long tick = match.tickNumber();

        if (fighter.reloading()) {
            return false;
        }

        if (fighter.magazine() <= 0) {
            reload(shooter);
            return false;
        }

        if (!fighter.fire(tick, fireInterval)) {
            return false;
        }

        Location origin = shooter.getEyeLocation();
        Vector direction = origin.getDirection().normalize();

        match.sounds().at(
                shooter.getLocation(), "classic-fire", 48
        );

        RayTraceResult result = origin.getWorld().rayTrace(
                origin,
                direction,
                maximumRange,
                FluidCollisionMode.NEVER,
                true,
                0.08,
                entity -> {
                    if (!(entity instanceof Player target)) {
                        return false;
                    }

                    if (target.getUniqueId().equals(shooter.getUniqueId())) {
                        return false;
                    }

                    Fighter targetFighter = match.fighter(
                            target.getUniqueId()
                    );

                    return targetFighter != null
                            && targetFighter.alive()
                            && match.map().containsPlayer(
                                    target.getLocation()
                            );
                }
        );

        Vector end = result == null
                ? origin.toVector().add(
                        direction.clone().multiply(maximumRange)
                )
                : result.getHitPosition();

        showTrace(origin, end);

        if (result == null
                || !(result.getHitEntity() instanceof Player target)) {
            return true;
        }

        Fighter victim = match.fighter(target.getUniqueId());

        if (victim == null || !victim.alive()) {
            return true;
        }

        if (!friendlyFire && victim.team() == fighter.team()) {
            return true;
        }

        double distance = origin.toVector().distance(
                result.getHitPosition()
        );

        double damage = distance <= nearDistance
                ? nearDamage
                : distance <= middleDistance
                    ? middleDamage
                    : farDamage;

        double eyeY = target.getEyeLocation().getY();
        double hitY = result.getHitPosition().getY();

        boolean headshot = hitY >= eyeY - headTolerance;

        if (headshot) {
            damage *= headMultiplier;

            shooter.sendMessage(
                    Component.text("헤드샷!", NamedTextColor.RED)
            );
        }

        match.damage(
                shooter.getUniqueId(),
                target.getUniqueId(),
                damage,
                headshot
        );

        return true;
    }

    public boolean reload(Player player) {
        Fighter fighter = match.fighter(player.getUniqueId());

        if (!canUse(player, fighter)) {
            return false;
        }

        if (!fighter.beginReload(
                match.tickNumber(),
                reloadDuration,
                magazineCapacity
        )) {
            return false;
        }

        match.sounds().play(player, "classic-reload");

        player.sendActionBar(
                Component.text("클래식 재장전 중…", NamedTextColor.AQUA)
        );

        return true;
    }

    public void tick() {
        for (Fighter fighter : match.fighters()) {
            if (!fighter.connected()) {
                continue;
            }

            if (fighter.finishReload(
                    match.tickNumber(), magazineCapacity
            )) {
                Player player = match.player(fighter.id());

                if (player != null) {
                    match.sounds().play(player, "classic-reload");
                }
            }
        }
    }

    public void cancelReloads() {
        for (Fighter fighter : match.fighters()) {
            fighter.cancelReload();
        }
    }

    private boolean canUse(Player player, Fighter fighter) {
        return match.phase().allowsCombat()
                && fighter != null
                && fighter.alive()
                && match.map().containsPlayer(player.getLocation())
                && match.items().is(
                        player.getInventory().getItemInMainHand(),
                        GameItems.CLASSIC,
                        match.id()
                );
    }

    private void showTrace(Location origin, Vector end) {
        Vector difference = end.clone().subtract(origin.toVector());
        double length = difference.length();

        if (length <= 0.001) {
            return;
        }

        Vector direction = difference.normalize();

        // 一発ごとの粒子数を制限します。
        int samples = Math.min(40, Math.max(1, (int) (length / 1.8)));

        for (int index = 1; index <= samples; index++) {
            Location point = origin.clone().add(
                    direction.clone().multiply(
                            length * index / samples
                    )
            );

            for (Fighter viewer : match.fighters()) {
                Player player = match.player(viewer.id());

                if (player != null
                        && player.getWorld().equals(point.getWorld())
                        && player.getLocation().distanceSquared(point)
                        <= 64 * 64) {
                    player.spawnParticle(
                            Particle.CRIT,
                            point,
                            1,
                            0,
                            0,
                            0,
                            0
                    );
                }
            }
        }
    }
}