package val.code.game;

import net.kyori.adventure.text.format.NamedTextColor;

public enum Team {

    RED("레드", NamedTextColor.RED),
    CYAN("청록", NamedTextColor.AQUA);

    private final String korean;
    private final NamedTextColor color;

    Team(String korean, NamedTextColor color) {
        this.korean = korean;
        this.color = color;
    }

    public String korean() {
        return korean;
    }

    public NamedTextColor color() {
        return color;
    }

    public Team opposite() {
        return this == RED ? CYAN : RED;
    }

    public String roleName(Team attackingTeam) {
        return this == attackingTeam ? "공격팀" : "수비팀";
    }
}