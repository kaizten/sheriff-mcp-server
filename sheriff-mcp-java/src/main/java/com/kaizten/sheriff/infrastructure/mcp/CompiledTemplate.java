package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.regex.Pattern;

/**
 * One rule's description, already compiled into a pattern.
 *
 * <p>Its own file rather than a nested record, because Sheriff's hexagonal
 * profile rejects nested types.
 *
 * @param pattern the compiled template
 * @param rule the rule it belongs to
 */
record CompiledTemplate(Pattern pattern, SheriffRule rule) {
}
