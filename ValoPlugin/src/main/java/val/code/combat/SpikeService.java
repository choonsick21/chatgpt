package val.code.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import val.code.config.Settings;
import val.code.game.Fighter;
import val.code.game.Match;
import val.code.item.GameItems;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class SpikeService {

    public enum State {
        NONE,
        CARRIED,
        DROPPED,
        PLANTED,
        RESOLVED
    }

    private enum ActionType {
        PLANT,
        DEFUSE
    }

    private final Match match;

    private final int plantTicks;
    private final int defuseTicks;
    private final int explosionTicks;

    private final double interactionRangeSquared;
    private final double cancelDistanceSquared;
    private final boolean requireSneaking;
    private final int plantReward;
    private final int maximumMoney;

    private final Map<UUID, Action> actions = new HashMap<>();

    private State state = State.NONE;
    private UUID carrier;
    private UUID defuser;

    private Item droppedItem;
    private TextDisplay plantedDisplay;

    private Location plantedLocation;
    private long explosionAt;

    public SpikeService(Match match) {
        this.match = match;

        Settings settings = match.settings();

        plantTicks = settings.ticks(
                "spike.plant-seconds", 4
        );
        defuseTicks = settings.ticks(
                "spike.defuse-seconds", 7
        );
        explosionTicks = settings.ticks(
                "spike.explosion-seconds", 40
        );

        double interactionRange = settings.decimal(
                "spike.interaction-range", 3, 0.5, 6
        );
        interactionRangeSquared = interactionRange * interactionRange;

        double cancelDistance = settings.decimal(
                "spike.movement-cancel-distance", 0.25, 0.05, 1
        );
        cancelDistanceSquared = cancelDistance * cancelDistance;

        requireSneaking = settings.bool(
                "spike.require-sneaking", true
        );
        plantReward = settings.integer(
                "economy.plant-reward", 300, 0, 1_000_000
        );
        maximumMoney = settings.integer(
                "economy.maximum-money", 9000, 0, 1_000_000
        );
    }

    public State state() {
        return state;
    }

    public boolean planted() {
        return state == State.PLANTED;
    }

    public UUID carrier() {
        return carrier;
    }

    public Location plantedLocation() {
        return plantedLocation == null
                ? null : plantedLocation.clone();
    }

    public long remainingExplosionTicks() {
        return state == State.PLANTED
                ? Math.max(0, explosionAt - match.tickNumber())
                : 0;
    }

    public boolean hasAction(UUID playerId) {
        return actions.containsKey(playerId);
    }

    public void startRound() {
        clear();

        for (Fighter fighter : match.fighters()) {
            if (fighter.alive()
                    && fighter.team() == match.attackingTeam()) {
                Player player = match.player(fighter.id());

                if (player != null) {
                    give(player);
                    return;
                }
            }
        }
    }

    public boolean interact(Player player) {
        if (!match.phase().allowsCombat()) {
            return false;
        }

        if (actions.containsKey(player.getUniqueId())) {
            return true;
        }

        Fighter fighter = match.fighter(player.getUniqueId());

        if (fighter == null || !fighter.alive()) {
            return false;
        }

        if (canPlant(player, fighter)) {
            return begin(player, ActionType.PLANT);
        }

        if (canDefuse(player, fighter)) {
            return begin(player, ActionType.DEFUSE);
        }

        return false;
    }

    public void tick() {
        if (!match.phase().allowsCombat()) {
            cancelAllActions();
            return;
        }

        long tick = match.tickNumber();

        if (state == State.PLANTED && tick >= explosionAt) {
            resolveExplosion();
            return;
        }

        if (requireSneaking) {
            for (Fighter fighter : match.fighters()) {
                if (!fighter.alive()
                        || actions.containsKey(fighter.id())) {
                    continue;
                }

                Player player = match.player(fighter.id());

                if (player != null && player.isSneaking()) {
                    interact(player);
                }
            }
        }

        Iterator<Map.Entry<UUID, Action>> iterator =
                actions.entrySet().iterator();

        Action completedAction = null;
        UUID completedPlayer = null;

        while (iterator.hasNext()) {
            Map.Entry<UUID, Action> entry = iterator.next();
            Player player = match.player(entry.getKey());
            Action action = entry.getValue();

            if (!validAction(player, action)) {
                if (player != null) {
                    player.sendActionBar(
                            Component.text(
                                    "스파이크 작업 취소",
                                    NamedTextColor.RED
                            )
                    );
                }

                if (entry.getKey().equals(defuser)) {
                    defuser = null;
                }

                iterator.remove();
                continue;
            }

            int duration = action.type() == ActionType.PLANT
                    ? plantTicks : defuseTicks;

            long elapsed = tick - action.startedAt();

            if (elapsed >= duration) {
                completedAction = action;
                completedPlayer = entry.getKey();
                iterator.remove();
                break;
            }

            if (tick % 4 == 0) {
                int percent = (int) Math.min(
                        100,
                        elapsed * 100 / duration
                );

                player.sendActionBar(
                        Component.text(
                                (action.type() == ActionType.PLANT
                                        ? "스파이크 설치 "
                                        : "스파이크 해체 ")
                                        + percent + "%",
                                NamedTextColor.AQUA
                        )
                );
            }
        }

        if (completedAction != null) {
            Player player = match.player(completedPlayer);

            if (player != null) {
                if (completedAction.type() == ActionType.PLANT) {
                    completePlant(player);
                } else {
                    completeDefuse(player);
                }
            }
        }

        if (state == State.PLANTED && tick % 20 == 0) {
            long seconds = (remainingExplosionTicks() + 19) / 20;

            if (plantedDisplay != null && plantedDisplay.isValid()) {
                plantedDisplay.text(
                        Component.text(
                                "스파이크 · " + seconds + "초",
                                NamedTextColor.RED
                        ).append(Component.newline())
                         .append(Component.text(
                                 "수비팀: 근처에서 웅크려 해체",
                                 NamedTextColor.GRAY
                         ))
                );
            }

            match.sounds().at(
                    plantedLocation, "spike-tick", 48
            );
        }
    }

    public boolean tryPickup(Player player, Item item) {
        if (state != State.DROPPED
                || droppedItem == null
                || !droppedItem.getUniqueId()
                .equals(item.getUniqueId())) {
            return false;
        }

        Fighter fighter = match.fighter(player.getUniqueId());

        if (fighter == null
                || !fighter.alive()
                || fighter.team() != match.attackingTeam()
                || !match.phase().isRoundActive()
                || !match.items().is(
                        item.getItemStack(),
                        GameItems.SPIKE,
                        match.id()
                )) {
            return false;
        }

        item.remove();
        droppedItem = null;
        give(player);

        match.announce(
                player.getName() + "님이 스파이크를 회수했습니다.",
                NamedTextColor.AQUA
        );

        return true;
    }

    public boolean isDroppedEntity(Item item) {
        return droppedItem != null
                && droppedItem.getUniqueId()
                .equals(item.getUniqueId());
    }

    public void dropCarrier(UUID playerId, Location location) {
        cancel(playerId);

        if (state != State.CARRIED
                || carrier == null
                || !carrier.equals(playerId)) {
            return;
        }

        Player player = match.player(playerId);

        if (player != null) {
            removeSpikeItems(player);
        }

        carrier = null;

        Location safe = location == null
                ? null : location.clone();

        if (safe == null || !match.map().contains(safe)) {
            safe = match.map().spawn(true, 0);
        }

        if (safe == null || safe.getWorld() == null) {
            state = State.NONE;
            return;
        }

        state = State.DROPPED;

        droppedItem = safe.getWorld().dropItem(
                safe.clone().add(0, 0.15, 0),
                match.items().spike(match.id())
        );

        droppedItem.setPickupDelay(0);
        droppedItem.setVelocity(new org.bukkit.util.Vector());
        droppedItem.setInvulnerable(true);
        droppedItem.setPersistent(false);
        droppedItem.setUnlimitedLifetime(true);
        droppedItem.setWillAge(false);

        match.announce(
                "스파이크가 떨어졌습니다. 공격팀이 회수할 수 있습니다.",
                NamedTextColor.RED
        );
    }

    public void cancel(UUID playerId) {
        actions.remove(playerId);

        if (playerId.equals(defuser)) {
            defuser = null;
        }
    }

    public void cancelAllActions() {
        actions.clear();
        defuser = null;
    }

    public void clear() {
        cancelAllActions();

        for (Fighter fighter : match.fighters()) {
            Player player = match.player(fighter.id());

            if (player != null) {
                removeSpikeItems(player);
            }
        }

        if (droppedItem != null) {
            droppedItem.remove();
            droppedItem = null;
        }

        if (plantedDisplay != null) {
            plantedDisplay.remove();
            plantedDisplay = null;
        }

        state = State.NONE;
        carrier = null;
        plantedLocation = null;
        explosionAt = 0;
    }

    private void give(Player player) {
        player.getInventory().setItem(
                7, match.items().spike(match.id())
        );

        carrier = player.getUniqueId();
        state = State.CARRIED;

        player.sendMessage(
                Component.text(
                        "스파이크를 받았습니다. 8번 슬롯을 사용하세요.",
                        NamedTextColor.RED
                )
        );
    }

    private boolean begin(Player player, ActionType type) {
        if (requireSneaking && !player.isSneaking()) {
            return false;
        }

        if (type == ActionType.DEFUSE) {
            if (defuser != null
                    && !defuser.equals(player.getUniqueId())) {
                return false;
            }

            defuser = player.getUniqueId();
        }

        actions.put(
                player.getUniqueId(),
                new Action(
                        type,
                        player.getLocation().clone(),
                        match.tickNumber()
                )
        );

        return true;
    }

    private boolean validAction(Player player, Action action) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        Fighter fighter = match.fighter(player.getUniqueId());

        if (fighter == null
                || !fighter.alive()
                || !match.phase().allowsCombat()
                || !player.getWorld()
                .equals(action.start().getWorld())
                || player.getLocation().distanceSquared(
                        action.start()
                ) > cancelDistanceSquared
                || (requireSneaking && !player.isSneaking())) {
            return false;
        }

        return action.type() == ActionType.PLANT
                ? canPlant(player, fighter)
                : canDefuse(player, fighter);
    }

    private boolean canPlant(Player player, Fighter fighter) {
        return state == State.CARRIED
                && player.getUniqueId().equals(carrier)
                && fighter.team() == match.attackingTeam()
                && match.map().isPlantSite(player.getLocation())
                && match.items().is(
                        player.getInventory().getItemInMainHand(),
                        GameItems.SPIKE,
                        match.id()
                );
    }

    private boolean canDefuse(Player player, Fighter fighter) {
        return state == State.PLANTED
                && fighter.team() != match.attackingTeam()
                && plantedLocation != null
                && player.getWorld()
                .equals(plantedLocation.getWorld())
                && match.map().containsPlayer(player.getLocation())
                && player.getLocation().distanceSquared(
                        plantedLocation
                ) <= interactionRangeSquared;
    }

    private void completePlant(Player player) {
        Fighter fighter = match.fighter(player.getUniqueId());

        if (fighter == null || !canPlant(player, fighter)) {
            return;
        }

        plantedLocation = player.getLocation().clone();
        explosionAt = match.tickNumber() + explosionTicks;

        removeSpikeItems(player);

        carrier = null;
        state = State.PLANTED;
        cancelAllActions();

        plantedDisplay = plantedLocation.getWorld().spawn(
                plantedLocation.clone().add(0, 0.55, 0),
                TextDisplay.class,
                display -> {
                    display.text(
                            Component.text(
                                    "스파이크 설치 완료",
                                    NamedTextColor.RED
                            )
                    );

                    display.setBillboard(Display.Billboard.CENTER);
                    display.setAlignment(
                            TextDisplay.TextAlignment.CENTER
                    );
                    display.setShadowed(true);
                    display.setDefaultBackground(true);
                    display.setInvulnerable(true);
                    display.setPersistent(false);
                    display.setViewRange(0.8F);
                }
        );

        fighter.addMoney(plantReward, maximumMoney);

        match.announce(
                player.getName() + "님이 스파이크를 설치했습니다.",
                NamedTextColor.RED
        );

        match.sounds().at(
                plantedLocation, "spike-plant", 64
        );
    }

    private void completeDefuse(Player player) {
        Fighter fighter = match.fighter(player.getUniqueId());

        if (fighter == null || !canDefuse(player, fighter)) {
            cancel(player.getUniqueId());
            return;
        }

        state = State.RESOLVED;
        cancelAllActions();

        if (plantedDisplay != null) {
            plantedDisplay.remove();
            plantedDisplay = null;
        }

        match.sounds().at(
                plantedLocation, "spike-defuse", 64
        );

        match.endRound(
                match.attackingTeam().opposite(),
                "스파이크 해체"
        );
    }

    private void resolveExplosion() {
        if (state != State.PLANTED) {
            return;
        }

        state = State.RESOLVED;
        cancelAllActions();

        if (plantedDisplay != null) {
            plantedDisplay.remove();
            plantedDisplay = null;
        }

        // 실제 폭발로 건축물이나 비참가자를 훼손하지 않습니다.
        match.sounds().at(
                plantedLocation, "spike-explode", 96
        );

        for (Fighter fighter : match.fighters()) {
            Player player = match.player(fighter.id());

            if (player != null
                    && player.getWorld()
                    .equals(plantedLocation.getWorld())) {
                player.spawnParticle(
                        org.bukkit.Particle.EXPLOSION_EMITTER,
                        plantedLocation,
                        1
                );
            }
        }

        match.endRound(
                match.attackingTeam(),
                "스파이크 폭발"
        );
    }

    private void removeSpikeItems(Player player) {
        ItemStack[] contents = player.getInventory().getContents();

        for (int index = 0; index < contents.length; index++) {
            if (match.items().is(
                    contents[index],
                    GameItems.SPIKE,
                    match.id()
            )) {
                player.getInventory().setItem(index, null);
            }
        }

        if (match.items().is(
                player.getItemOnCursor(),
                GameItems.SPIKE,
                match.id()
        )) {
            player.setItemOnCursor(null);
        }
    }

    private record Action(
            ActionType type,
            Location start,
            long startedAt
    ) {
    }
}