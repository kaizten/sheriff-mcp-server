package com.kaizten.sheriff.domain.enumerate;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Why a run of the fix loop ended.
 *
 * <p>An enum rather than the free strings the Python original compared
 * against: a typo in {@code "success_after_repair"} is a bug no test catches,
 * and this is the value a caller branches on. {@link #wireName()} keeps the
 * exact spelling {@code --json-report} has always emitted, so the reports
 * consumers already parse do not change.
 */
public enum StopReason {

    /**
     * Sheriff reported no errors and the project's own tests passed.
     */
    SUCCESS("success"),

    /**
     * The same, after one unrestricted repair pass fixed a broken caller.
     */
    SUCCESS_AFTER_REPAIR("success_after_repair"),

    /**
     * Every file that still had errors had been tried without improving.
     */
    STALLED("stalled"),

    /**
     * The fixer itself failed to run.
     */
    FIXER_FAILED("fixer_failed"),

    /**
     * The fixer modified files Sheriff had not flagged.
     */
    OUT_OF_SCOPE("out_of_scope"),

    /**
     * Sheriff could not run at all.
     */
    INFRA_ERROR("infra_error"),

    /**
     * Sheriff went quiet but the tests stayed red, repair included.
     */
    REPAIR_FAILED("repair_failed"),

    /**
     * The iteration cap was reached with errors still outstanding.
     */
    ITERATIONS_EXHAUSTED("iterations_exhausted");

    private static final String ERROR_UNKNOWN_NAME = "No such stop reason: ";
    private static final int NOT_FOUND = -1;

    private final String wireName;

    StopReason(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The spelling used in {@code --json-report} output.
     *
     * @return the wire name of this reason
     */
    public String wireName() {
        return wireName;
    }

    /**
     * The constant with this exact name.
     *
     * <p>Case-sensitive on purpose: {@code "success"} is the wire name and
     * {@code SUCCESS} is the constant, and quietly accepting either is how a
     * report ends up carrying a spelling nothing else recognises.
     *
     * @param name the constant's name
     * @return the constant it names
     * @throws NullPointerException when {@code name} is null
     * @throws IllegalArgumentException when no constant has that name
     */
    public static StopReason fromString(String name) {
        Objects.requireNonNull(name);
        for (StopReason reason : values()) {
            if (reason.name().equals(name)) {
                return reason;
            }
        }
        throw new IllegalArgumentException(ERROR_UNKNOWN_NAME + name);
    }

    /**
     * Where a named constant sits in the declaration order.
     *
     * @param name the constant's name
     * @return its index, or -1 when nothing has that name
     */
    public static int indexOf(String name) {
        if (name == null) {
            return NOT_FOUND;
        }
        StopReason[] reasons = values();
        for (int index = 0; index < reasons.length; index++) {
            if (reasons[index].name().equals(name)) {
                return index;
            }
        }
        return NOT_FOUND;
    }

    /**
     * Whether a name belongs to a constant.
     *
     * @param name the name to check
     * @return {@code true} when a constant has exactly that name
     */
    public static boolean isValid(String name) {
        return indexOf(name) != NOT_FOUND;
    }

    /**
     * Any one of the constants.
     *
     * @return one of them, chosen arbitrarily
     */
    public static StopReason random() {
        StopReason[] reasons = values();
        return reasons[ThreadLocalRandom.current().nextInt(reasons.length)];
    }
}
