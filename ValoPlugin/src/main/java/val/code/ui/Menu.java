package val.code.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class Menu implements InventoryHolder {

    private final JavaPlugin plugin;
    private final UUID viewer;
    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    private long lastClickTick = Long.MIN_VALUE;

    public Menu(
            JavaPlugin plugin,
            Player viewer,
            int rows,
            String title
    ) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("GUI 행 수는 1~6이어야 합니다.");
        }

        this.plugin = plugin;
        this.viewer = viewer.getUniqueId();

        Component header = Component.text("VALO ", NamedTextColor.RED)
                .append(Component.text("│ " + title, NamedTextColor.WHITE));

        this.inventory = Bukkit.createInventory(this, rows * 9, header);
        fill();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID viewer() {
        return viewer;
    }

    public Menu button(
            int slot,
            ItemStack item,
            Consumer<Player> action
    ) {
        if (slot < 0 || slot >= inventory.getSize()) {
            throw new IllegalArgumentException("잘못된 GUI 슬롯: " + slot);
        }

        inventory.setItem(slot, item);

        if (action == null) {
            actions.remove(slot);
        } else {
            actions.put(slot, action);
        }

        return this;
    }

    public Menu decoration(int slot, ItemStack item) {
        return button(slot, item, null);
    }

    public void open(Player player) {
        if (!viewer.equals(player.getUniqueId())) {
            throw new IllegalArgumentException("다른 플레이어의 메뉴입니다.");
        }
        player.openInventory(inventory);
    }

    /**
     * 이벤트 리스너에서 기본 인벤토리 동작을 취소한 후 호출합니다.
     * 한 틱에 여러 패킷이 와도 한 번만 처리합니다.
     */
    public void click(Player player, int rawSlot) {
        if (!viewer.equals(player.getUniqueId())
                || rawSlot < 0
                || rawSlot >= inventory.getSize()) {
            return;
        }

        if (player.getOpenInventory().getTopInventory().getHolder() != this) {
            return;
        }

        long currentTick = Bukkit.getCurrentTick();

        if (lastClickTick == currentTick) {
            return;
        }

        Consumer<Player> action = actions.get(rawSlot);

        if (action == null) {
            return;
        }

        lastClickTick = currentTick;

        // InventoryClickEvent 안에서 직접 GUI를 교체하지 않습니다.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()
                    || player.getOpenInventory()
                    .getTopInventory().getHolder() != this) {
                return;
            }
            action.accept(player);
        });
    }

    public static ItemStack item(
            Material material,
            String title,
            NamedTextColor color,
            String... lore
    ) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(
                Component.text(title, color)
                        .decoration(TextDecoration.ITALIC, false)
        );

        meta.lore(
                List.of(lore).stream()
                        .map(line -> Component.text(line, NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false))
                        .toList()
        );

        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS
        );

        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack head(
            OfflinePlayer owner,
            String actualName,
            boolean leader,
            String... lore
    ) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();

        meta.setOwningPlayer(owner);

        meta.displayName(
                Component.text(
                        (leader ? "★ " : "") + actualName,
                        leader ? NamedTextColor.RED : NamedTextColor.WHITE
                ).decoration(TextDecoration.ITALIC, false)
        );

        meta.lore(
                List.of(lore).stream()
                        .map(line -> Component.text(line, NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false))
                        .toList()
        );

        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack back() {
        return item(
                Material.ARROW,
                "뒤로가기",
                NamedTextColor.AQUA,
                "이전 메뉴로 돌아갑니다."
        );
    }

    public static ItemStack close() {
        return item(
                Material.BARRIER,
                "닫기",
                NamedTextColor.RED,
                "메뉴를 닫습니다."
        );
    }

    private void fill() {
        ItemStack dark = item(
                Material.BLACK_STAINED_GLASS_PANE,
                " ",
                NamedTextColor.DARK_GRAY
        );

        ItemStack edge = item(
                Material.GRAY_STAINED_GLASS_PANE,
                " ",
                NamedTextColor.DARK_GRAY
        );

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;

            boolean border = row == 0
                    || row == inventory.getSize() / 9 - 1
                    || column == 0
                    || column == 8;

            inventory.setItem(slot, border ? edge : dark);
        }

        inventory.setItem(0, item(
                Material.RED_STAINED_GLASS_PANE,
                "VALO",
                NamedTextColor.RED
        ));

        inventory.setItem(8, item(
                Material.CYAN_STAINED_GLASS_PANE,
                "전술 훈련장",
                NamedTextColor.AQUA
        ));
    }
}