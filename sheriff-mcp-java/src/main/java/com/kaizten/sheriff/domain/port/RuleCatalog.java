package com.kaizten.sheriff.domain.port;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.List;

/**
 * Every rule Sheriff knows about, not just the ones already broken.
 *
 * <p>The other analysis port answers "what is wrong with this code?"; this one
 * answers "what does Sheriff expect of code that does not exist yet?".
 */
@FunctionalInterface
public interface RuleCatalog {

    /**
     * The whole catalog.
     *
     * @return every rule, or an empty list when no catalog is available — that
     *     is a supported state, not a misconfiguration
     */
    List<SheriffRule> allRules();

    /**
     * Whether the rules can be read right now, without waiting for them to be
     * provisioned. A catalog that is simply there always can.
     *
     * @return {@code true} when {@link #allRules()} answers at once
     */
    default boolean availableNow() {
        return true;
    }
}
