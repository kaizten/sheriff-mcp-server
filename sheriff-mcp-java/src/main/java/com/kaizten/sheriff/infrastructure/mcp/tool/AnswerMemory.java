package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The errors this session last listed in full, per component, so that the
 * same list is not sent twice.
 *
 * <p>On PetClinic, a session called {@code sheriff_fix} three times in a row
 * with nothing edited between them, and got the same 172 errors, 16 KB each
 * time. Whether a list is the same is a fact about the errors, so the answer
 * to it is the same whichever model asks.
 */
final class AnswerMemory {

    private static final String FIELD_SEPARATOR = "|";
    private static final String LINE_SEPARATOR = "\n";

    private final Map<String, String> listed = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> shown = new ConcurrentHashMap<>();

    /**
     * Records the errors listed for a component, and says whether they are
     * exactly those listed for it last time.
     *
     * @param component the component
     * @param errors the errors now
     * @return {@code true} when the last list for the component was the same
     */
    boolean sameAsLastTime(String component, List<SheriffFinding> errors) {
        String signature = String.join(LINE_SEPARATOR, errors.stream()
                .map(error -> error.file() + FIELD_SEPARATOR + error.referenceCode() + FIELD_SEPARATOR
                        + error.description())
                .sorted().toList());
        return signature.equals(listed.put(component, signature));
    }

    /**
     * The errors the last answer for a component listed one by one, as
     * {@link com.kaizten.sheriff.infrastructure.hook.StuckErrors#key} knows
     * them: the ones the model was asked to fix.
     *
     * @param component the component
     * @return their keys, none before the first answer
     */
    Set<String> lastShown(String component) {
        return shown.getOrDefault(component, Set.of());
    }

    /**
     * Records the errors an answer listed one by one.
     *
     * @param component the component
     * @param keys their keys
     */
    void shown(String component, Set<String> keys) {
        shown.put(component, Set.copyOf(keys));
    }
}
