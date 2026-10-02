package val.code.combat;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import val.code.config.Settings;
import val.code.game.Fighter;
import val.code.game.Match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JettService {

    private final Match match;

    private final double dashDistance;
    private final double dashStep;
    private final double collisionPadding;
    private final int dashCooldown;

    private final double smokeRange;
    private final double smokeRadius;
    private final int smokeDuration;
    private final int smokeCooldown;
    private final int maximumSmokes;
    private final int updateInterval;
    private final int interiorParticles;
    private final double viewerDistanceSquared;
    private final boolean darknessInside;

    private final List<Vector> surfaceOffsets;
    private final List<Smoke> smokes = new ArrayList<>();

    private final Map<UUID, DarknessBackup> darknessBackups =
            new HashMap<>();

    private final Particle.DustOptions smokeDust =
            new Particle.DustOptions(
                    Color.fromRGB(175, 205, 215),
                    1.8F
            );

    public JettService(Match match) {
        this.match = match;

        Settings settings = match.settings();

        dashDistance = settings.decimal(
                "jett.dash.distance", 5.5, 0.5, 12
        );
        dashStep = settings.decimal(
                "jett.dash.step-size", 0.20, 0.05, 0.25
        );
        collisionPadding = settings.decimal(
                "jett.dash.collision-padding", 0.32, 0.30, 0.45
        );
        dashCooldown = settings.integer(
                "jett.dash.cooldown-ticks", 20, 1, 1200
        );

        smokeRange = settings.decimal(
                "jett.smoke.maximum-range", 18, 1, 64
        );
        smokeRadius = settings.decimal(
                "jett.smoke.radius", 2.8, 1, 6
        );
        smokeDuration = settings.ticks(
                "jett.smoke.duration-seconds", 7
        );
        smokeCooldown = settings.integer(
                "jett.smoke.cooldown-ticks", 12, 1, 1200
        );
        maximumSmokes = settings.integer(
                "jett.smoke.maximum-active-per-match", 8, 1, 16
        );
        updateInterval = settings.integer(
                "jett.smoke.update-interval-ticks", 4, 2, 20
        );
        interiorParticles = settings.integer(
                "jett.smoke.interior-particles", 24, 0, 100
        );

        double viewerDistance = settings.decimal(
                "jett.smoke.viewer-distance", 48, 8, 96
        );

        viewerDistanceSquared = viewerDistance * viewerDistance;

        darknessInside = settings.bool(
                "jett.smoke.apply-darkness-inside", true
        );

        int surfaceParticles = settings.integer(
                "jett.smoke.surface-particles", 180, 24, 600
        );

        surfaceOffsets = buildSphere(
                surfaceParticles, smokeRadius
        );
    }

    public boolean dash(Player player) {
        Fighter fighter = match.fighter(player.getUniqueId());

        if (!canUse(player, fighter)
                || !fighter.dashAvailable(match.tickNumber())) {
            return false;
        }

        Location start = player.getLocation();

        Vector direction = start.getDirection();
        direction.setY(0);

        if (direction.lengthSquared() < 0.0001) {
            double yaw = Math.toRadians(start.getYaw());

            direction = new Vector(
                    -Math.sin(yaw),
                    0,
                    Math.cos(yaw)
            );
        }

        direction.normalize();

        Location destination = start.clone();

        for (double distance = dashStep;
             distance <= dashDistance + 0.0001;
             distance += dashStep) {

            double limitedDistance = Math.min(
                    dashDistance, distance
            );

            Location candidate = start.clone().add(
                    direction.clone().multiply(limitedDistance)
            );

            if (!safeBody(candidate)) {
                break;
            }

            destination = candidate;
        }

        if (start.distanceSquared(destination) < 0.04) {
            return false;
        }

        // 移動が他プラグインに拒否された場合は充電を消費しません。
        if (!player.teleport(destination)) {
            return false;
        }

        if (!fighter.consumeDash(
                match.tickNumber(), dashCooldown
        )) {
            player.teleport(start);
            return false;
        }

        player.setVelocity(new Vector());
        player.setFallDistance(0);

        match.sounds().at(start, "dash", 32);
        refreshItems(player, fighter);

        return true;
    }

    public boolean smoke(Player player) {
        Fighter fighter = match.fighter(player.getUniqueId());

        if (!canUse(player, fighter)
                || !fighter.smokeAvailable(match.tickNumber())
                || smokes.size() >= maximumSmokes) {
            return false;
        }

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        RayTraceResult hit = eye.getWorld().rayTraceBlocks(
                eye,
                direction,
                smokeRange,
                FluidCollisionMode.NEVER,
                true
        );

        double distance = smokeRange;

        if (hit != null) {
            distance = Math.max(
                    0.5,
                    eye.toVector().distance(hit.getHitPosition()) - 0.4
            );
        }

        Location center = eye.clone();

        // 목표를 맵 안에 제한합니다. 벽 충돌 거리 이상으로 진행하지 않습니다.
        for (double step = 0.25;
             step <= distance + 0.0001;
             step += 0.25) {

            Location candidate = eye.clone().add(
                    direction.clone().multiply(Math.min(step, distance))
            );

            if (!match.map().contains(candidate)
                    || !candidate.getBlock().isPassable()) {
                break;
            }

            center = candidate;
        }

        if (!match.map().contains(center)) {
            return false;
        }

        if (!fighter.consumeSmoke(
                match.tickNumber(), smokeCooldown
        )) {
            return false;
        }

        smokes.add(new Smoke(
                center.clone(),
                match.tickNumber() + smokeDuration
        ));

        match.sounds().at(player.getLocation(), "smoke", 32);
        refreshItems(player, fighter);

        return true;
    }

    public void tick() {
        long tick = match.tickNumber();

        smokes.removeIf(smoke -> smoke.expiresAt() <= tick);

        if (tick % updateInterval != 0) {
            return;
        }

        Set<UUID> inside = new HashSet<>();

        for (Smoke smoke : smokes) {
            drawSmoke(smoke);

            if (!darknessInside) {
                continue;
            }

            for (Fighter fighter : match.fighters()) {
                if (!fighter.alive()) {
                    continue;
                }

                Player player = match.player(fighter.id());

                if (player == null
                        || !player.getWorld()
                        .equals(smoke.center().getWorld())) {
                    continue;
                }

                if (player.getEyeLocation().distanceSquared(
                        smoke.center()
                ) <= smokeRadius * smokeRadius) {
                    inside.add(player.getUniqueId());
                }
            }
        }

        for (UUID id : inside) {
            Player player = match.player(id);

            if (player == null) {
                continue;
            }

            darknessBackups.computeIfAbsent(
                    id,
                    ignored -> new DarknessBackup(
                            player.getPotionEffect(
                                    PotionEffectType.DARKNESS
                            ),
                            tick
                    )
            );

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.DARKNESS,
                    updateInterval + 12,
                    0,
                    false,
                    false,
                    false
            ));
        }

        Iterator<Map.Entry<UUID, DarknessBackup>> iterator =
                darknessBackups.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, DarknessBackup> entry = iterator.next();

            if (!inside.contains(entry.getKey())) {
                restoreDarkness(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
    }

    public int activeSmokeCount() {
        return smokes.size();
    }

    public void removePlayer(UUID playerId) {
        DarknessBackup backup = darknessBackups.remove(playerId);

        if (backup != null) {
            restoreDarkness(playerId, backup);
        }
    }

    public void clear() {
        smokes.clear();

        for (Map.Entry<UUID, DarknessBackup> entry :
                darknessBackups.entrySet()) {
            restoreDarkness(entry.getKey(), entry.getValue());
        }

        darknessBackups.clear();
    }

    private boolean canUse(Player player, Fighter fighter) {
        return match.phase().allowsCombat()
                && fighter != null
                && fighter.alive()
                && fighter.agentConfirmed()
                && fighter.selectedAgent() == Fighter.Agent.JETT
                && match.map().containsPlayer(player.getLocation());
    }

    private boolean safeBody(Location feet) {
        if (!match.map().containsPlayer(feet)) {
            return false;
        }

        double[] horizontal = {
                -collisionPadding,
                0,
                collisionPadding
        };

        // 보수적인 블록 검사: 통과 불가능 블록이 몸체에 닿으면 중단.
        for (double x : horizontal) {
            for (double z : horizontal) {
                for (double y : new double[]{0.05, 0.85, 1.75}) {
                    Location sample = feet.clone().add(x, y, z);

                    if (!sample.getBlock().isPassable()
                            || sample.getBlock().isLiquid()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private void refreshItems(Player player, Fighter fighter) {
        player.getInventory().setItem(
                5,
                match.items().dash(
                        match.id(), fighter.dashCharges()
                )
        );

        player.getInventory().setItem(
                6,
                match.items().smoke(
                        match.id(), fighter.smokeCharges()
                )
        );
    }

    private void drawSmoke(Smoke smoke) {
        for (Fighter fighter : match.fighters()) {
            Player viewer = match.player(fighter.id());

            if (viewer == null
                    || !viewer.getWorld()
                    .equals(smoke.center().getWorld())
                    || viewer.getLocation().distanceSquared(
                            smoke.center()
                    ) > viewerDistanceSquared) {
                continue;
            }

            for (Vector offset : surfaceOffsets) {
                Location point = smoke.center().clone().add(offset);

                viewer.spawnParticle(
                        Particle.DUST,
                        point,
                        1,
                        0,
                        0,
                        0,
                        0,
                        smokeDust
                );
            }

            viewer.spawnParticle(
                    Particle.CLOUD,
                    smoke.center(),
                    interiorParticles,
                    smokeRadius * 0.42,
                    smokeRadius * 0.42,
                    smokeRadius * 0.42,
                    0.005
            );
        }
    }

    private void restoreDarkness(
            UUID id,
            DarknessBackup backup
    ) {
        Player player = match.player(id);

        if (player == null) {
            return;
        }

        player.removePotionEffect(PotionEffectType.DARKNESS);

        PotionEffect original = backup.effect();

        if (original == null) {
            return;
        }

        int remaining;

        if (original.getDuration() == -1) {
            remaining = -1;
        } else {
            long elapsed = Math.max(
                    0,
                    match.tickNumber() - backup.capturedAt()
            );

            remaining = (int) Math.max(
                    0,
                    original.getDuration() - elapsed
            );
        }

        if (remaining == 0) {
            return;
        }

        player.addPotionEffect(new PotionEffect(
                original.getType(),
                remaining,
                original.getAmplifier(),
                original.isAmbient(),
                original.hasParticles(),
                original.hasIcon()
        ));
    }

    private static List<Vector> buildSphere(
            int count,
            double radius
    ) {
        List<Vector> result = new ArrayList<>(count);
        double goldenAngle = Math.PI * (3 - Math.sqrt(5));

        for (int index = 0; index < count; index++) {
            double y = 1 - 2.0 * (index + 0.5) / count;
            double horizontal = Math.sqrt(
                    Math.max(0, 1 - y * y)
            );
            double angle = goldenAngle * index;

            result.add(new Vector(
                    Math.cos(angle) * horizontal * radius,
                    y * radius,
                    Math.sin(angle) * horizontal * radius
            ));
        }

        return List.copyOf(result);
    }

    private record Smoke(Location center, long expiresAt) {
    }

    private record DarknessBackup(
            PotionEffect effect,
            long capturedAt
    ) {
    }
}