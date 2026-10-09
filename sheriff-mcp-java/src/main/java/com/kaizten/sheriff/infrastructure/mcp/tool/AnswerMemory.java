package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.List;
import java.util.Map;
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
}
