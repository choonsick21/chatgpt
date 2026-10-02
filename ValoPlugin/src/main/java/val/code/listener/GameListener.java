package val.code.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;
import val.code.game.Match;
import val.code.game.MatchService;
import val.code.item.GameItems;

import java.util.Locale;

public final class GameListener implements Listener {

    private final JavaPlugin plugin;
    private final MatchService matches;
    private final GameItems items;

    public GameListener(
            JavaPlugin plugin,
            MatchService matches,
            GameItems items
    ) {
        this.plugin = plugin;
        this.matches = matches;
        this.items = items;
    }

    /*
     * 전투 입력
     */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (matches.of(player) == null) {
            return;
        }

        // 문, 버튼, 상자, 아이템의 바닐라 동작을 차단합니다.
        // 우클릭 이벤트는 원래 취소되어 있어도 무기 입력으로 처리합니다.
        event.setCancelled(true);

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR
                && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        try {
            matches.handleRightClick(player);
        } catch (IllegalArgumentException exception) {
            error(player, exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (matches.of(event.getPlayer()) == null) {
            return;
        }

        // F키를 재장전 입력으로 사용합니다.
        event.setCancelled(true);
        matches.handleReload(event.getPlayer());
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onHeldSlot(PlayerItemHeldEvent event) {
        if (matches.of(event.getPlayer()) == null) {
            return;
        }

        matches.handleSlot(
                event.getPlayer(),
                event.getNewSlot()
        );
    }

    /*
     * 아이템과 인벤토리 보호
     */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (matches.of(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (matches.of(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && matches.of(player) != null) {
            event.setCancelled(true);
        }

        // Menus는 취소된 이벤트도 받고 자체 버튼을 실행합니다.
        // 경기 인벤토리 이동만 막으며 GUI 버튼 처리는 Menus가 담당합니다.
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && matches.of(player) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || matches.of(player) == null) {
            return;
        }

        InventoryType type = event.getInventory().getType();

        // 플러그인의 CHEST형 GUI는 허용합니다.
        // 상자 블록과 엔티티 상호작용은 별도 이벤트에서 차단합니다.
        if (type != InventoryType.CHEST
                && type != InventoryType.CREATIVE
                && type != InventoryType.CRAFTING) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();

        // 일반 획득을 취소하고 서비스에서 공격팀 여부를 검사합니다.
        for (Match match : matches.all()) {
            if (!match.spike().isDroppedEntity(item)) {
                continue;
            }

            event.setCancelled(true);

            if (event.getEntity() instanceof Player player) {
                match.spike().tryPickup(player, item);
            }

            return;
        }

        if (event.getEntity() instanceof Player player
                && matches.of(player) != null) {
            event.setCancelled(true);
            return;
        }

        // 경기 종료 후 남은 플러그인 아이템의 일반 획득도 차단합니다.
        if (items.isPluginItem(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    /*
     * 피해와 사망
     */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        Match match = matches.of(player);

        if (match == null) {
            return;
        }

        boolean previouslyCancelled = event.isCancelled();

        // 체력 처리는 Match로 통일합니다.
        event.setCancelled(true);

        if (previouslyCancelled) {
            return;
        }

        // 근접 공격·바닐라 투사체 등은 사용하지 않습니다.
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }

        switch (event.getCause()) {
            case FALL,
                 FIRE,
                 FIRE_TICK,
                 LAVA,
                 DROWNING,
                 SUFFOCATION,
                 CONTACT,
                 HOT_FLOOR,
                 FREEZE,
                 STARVATION,
                 VOID,
                 FLY_INTO_WALL -> match.environmentDamage(
                    player.getUniqueId(),
                    event.getFinalDamage()
            );

            default -> {
                // 그 밖의 바닐라 피해는 경기 규칙에 포함하지 않습니다.
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event.getDamager());

        // 참가자가 비참가자나 일반 엔티티를 공격하는 것도 차단합니다.
        if (attacker != null && matches.of(attacker) != null) {
            event.setCancelled(true);
        }

        if (event.getEntity() instanceof Player victim
                && matches.of(victim) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Match match = matches.of(player);

        if (match == null) {
            return;
        }

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.deathMessage(null);

        match.externalDeath(player.getUniqueId());

        // 다른 플러그인 등으로 실제 사망한 경우 자동 리스폰을 시도합니다.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Match match = matches.of(player);

        if (match != null) {
            Location safe = match.safeLocation(player.getUniqueId());

            if (safe != null) {
                event.setRespawnLocation(safe);
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            Match current = matches.of(player);

            if (current != null) {
                current.afterRespawn(player);
            } else {
                // 사망 중 경기 종료되어 복원이 유예된 경우도 처리합니다.
                matches.recover(player);
            }
        });
    }

    /*
     * 이동과 맵 경계
     */

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {
        // 텔레포트는 별도 핸들러에서 검사합니다.
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        Location to = event.getTo();

        if (to == null || samePosition(event.getFrom(), to)) {
            return;
        }

        if (!matches.movementAllowed(event.getPlayer(), to)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        if (!matches.movementAllowed(
                event.getPlayer(),
                event.getTo()
        )) {
            event.setCancelled(true);
        }
    }

    /*
     * 건축물과 엔티티 상호작용
     */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (matches.of(event.getPlayer()) != null
                || insideActiveMap(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (matches.of(event.getPlayer()) != null
                || insideActiveMap(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (matches.of(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (matches.of(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    /*
     * 명령어와 접속 상태
     */

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (matches.of(player) == null) {
            return;
        }

        // 관리자는 긴급 운영 명령을 사용할 수 있습니다.
        if (player.hasPermission("valo.admin")) {
            return;
        }

        String message = event.getMessage().trim();
        String command = message.split("\\s+", 2)[0]
                .toLowerCase(Locale.ROOT);

        if (command.equals("/valo")) {
            return;
        }

        event.setCancelled(true);
        error(
                player,
                "경기 중에는 /valo 명령만 사용할 수 있습니다."
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                matches.recover(player);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        matches.onQuit(event.getPlayer());
    }

    private boolean insideActiveMap(Location location) {
        for (Match match : matches.all()) {
            if (match.map().contains(location)) {
                return true;
            }
        }

        return false;
    }

    private static Player attackingPlayer(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }

        if (entity instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }

        return null;
    }

    private static boolean samePosition(Location from, Location to) {
        return from.getWorld().equals(to.getWorld())
                && from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ();
    }

    private static void error(Player player, String message) {
        player.sendActionBar(
                Component.text(
                        message == null
                                ? "현재 사용할 수 없습니다."
                                : message,
                        NamedTextColor.RED
                )
        );
    }
}