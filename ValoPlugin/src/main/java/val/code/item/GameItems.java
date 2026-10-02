package val.code.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import val.code.config.Settings;

import java.util.List;
import java.util.UUID;

public final class GameItems {

    public static final String CLASSIC = "classic";
    public static final String SHOP = "shop";
    public static final String DASH = "dash";
    public static final String SMOKE = "smoke";
    public static final String SPIKE = "spike";
    public static final String SPECTATE = "spectate";

    private final NamespacedKey typeKey;
    private final NamespacedKey matchKey;
    private final Settings settings;

    public GameItems(JavaPlugin plugin, Settings settings) {
        this.settings = settings;
        this.typeKey = new NamespacedKey(plugin, "item-type");
        this.matchKey = new NamespacedKey(plugin, "match-id");
    }

    public ItemStack classic(UUID match) {
        return create(
                Material.IRON_HOE,
                CLASSIC,
                match,
                Component.text("클래식", NamedTextColor.WHITE),
                List.of(
                        line("우클릭: 발사", NamedTextColor.GRAY),
                        line("F: 재장전", NamedTextColor.AQUA),
                        line("매 라운드 기본 지급", NamedTextColor.DARK_GRAY)
                )
        );
    }

    public ItemStack shop(UUID match) {
        return create(
                Material.EMERALD,
                SHOP,
                match,
                Component.text("상점 · 5", NamedTextColor.AQUA),
                List.of(
                        line("5번 슬롯 선택 시 열기", NamedTextColor.GRAY),
                        line("구매 단계에서만 이용 가능", NamedTextColor.DARK_GRAY)
                )
        );
    }

    public ItemStack dash(UUID match, int charges) {
        return create(
                Material.FEATHER,
                DASH,
                match,
                Component.text("제트 대시 · 6", NamedTextColor.WHITE),
                List.of(
                        line("6번 슬롯 선택 즉시 사용", NamedTextColor.GRAY),
                        line("남은 충전: " + charges, NamedTextColor.AQUA)
                )
        );
    }

    public ItemStack smoke(UUID match, int charges) {
        return create(
                Material.SNOWBALL,
                SMOKE,
                match,
                Component.text("제트 연막 · 7", NamedTextColor.WHITE),
                List.of(
                        line("7번 슬롯 선택 즉시 사용", NamedTextColor.GRAY),
                        line("남은 충전: " + charges, NamedTextColor.AQUA)
                )
        );
    }

    public ItemStack spike(UUID match) {
        return create(
                Material.AMETHYST_SHARD,
                SPIKE,
                match,
                Component.text("스파이크", NamedTextColor.RED),
                List.of(
                        line("공격팀 전용", NamedTextColor.GRAY),
                        line("A/B 구역에서 들고 웅크리기 유지",
                                NamedTextColor.AQUA)
                )
        );
    }

    public ItemStack spectate(UUID match) {
        return create(
                Material.COMPASS,
                SPECTATE,
                match,
                Component.text("관전 대상 선택", NamedTextColor.AQUA),
                List.of(line("우클릭으로 관전 메뉴", NamedTextColor.GRAY))
        );
    }

    public String type(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }

        return stack.getItemMeta().getPersistentDataContainer()
                .get(typeKey, PersistentDataType.STRING);
    }

    public UUID match(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }

        String value = stack.getItemMeta().getPersistentDataContainer()
                .get(matchKey, PersistentDataType.STRING);

        if (value == null) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean is(ItemStack stack, String expected, UUID match) {
        return expected.equals(type(stack))
                && match != null
                && match.equals(match(stack));
    }

    public boolean isPluginItem(ItemStack stack) {
        return type(stack) != null;
    }

    private ItemStack create(
            Material material,
            String type,
            UUID match,
            Component name,
            List<Component> lore
    ) {
        if (match == null) {
            throw new IllegalArgumentException("경기 ID가 필요합니다.");
        }

        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_UNBREAKABLE
        );

        meta.getPersistentDataContainer().set(
                typeKey, PersistentDataType.STRING, type
        );
        meta.getPersistentDataContainer().set(
                matchKey, PersistentDataType.STRING, match.toString()
        );

        int model = settings.integer(
                "ui.models." + type, 0, 0, Integer.MAX_VALUE
        );

        if (model > 0) {
            meta.setCustomModelData(model);
        }

        stack.setItemMeta(meta);
        return stack;
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color)
                .decoration(TextDecoration.ITALIC, false);
    }
}