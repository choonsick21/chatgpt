package val.code.game;

public enum MatchPhase {

    PREPARING("맵 준비"),
    LOADING("맵 로딩 연출"),
    SELECTING("요원 선택"),
    COUNTDOWN("시작 카운트다운"),
    BUY("구매 단계"),
    COMBAT("전투 단계"),
    ROUND_END("라운드 결과"),
    RESULT("경기 결과"),
    CLOSED("종료");

    private final String korean;

    MatchPhase(String korean) {
        this.korean = korean;
    }

    public String korean() {
        return korean;
    }

    public boolean allowsCombat() {
        return this == COMBAT;
    }

    public boolean allowsBuying() {
        return this == BUY;
    }

    public boolean isRoundActive() {
        return this == BUY || this == COMBAT;
    }

    public boolean isFinished() {
        return this == RESULT || this == CLOSED;
    }

    /**
     * 시작 준비와 시각적 로딩 연출을 별도 상태로 구분합니다.
     * CLOSED로의 이동은 모든 상태에서 허용합니다.
     */
    public boolean canTransitionTo(MatchPhase next) {
        if (next == null || next == this) {
            return false;
        }

        if (next == CLOSED) {
            return this != CLOSED;
        }

        return switch (this) {
            case PREPARING -> next == LOADING;
            case LOADING -> next == SELECTING;
            case SELECTING -> next == COUNTDOWN;
            case COUNTDOWN -> next == BUY;
            case BUY -> next == COMBAT || next == ROUND_END || next == RESULT;
            case COMBAT -> next == ROUND_END || next == RESULT;
            case ROUND_END -> next == BUY || next == RESULT;
            case RESULT, CLOSED -> false;
        };
    }
}