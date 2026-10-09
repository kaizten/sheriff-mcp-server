package com.kaizten.sheriff.infrastructure.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the catalog the extractor pulls out of the Sheriff image.
 *
 * <p>A missing or unreadable file yields no rules rather than an error. That
 * is a supported state, not a misconfiguration: the agent worked before
 * catalogs existed and must keep working when one is absent — losing the rules
 * must never stop the fixing.
 */
public final class JsonRuleCatalog implements RuleCatalog {

    private static final String RULES_FIELD = "rules";
    private static final String CODE_FIELD = "code";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String HOW_TO_SOLVE_FIELD = "how_to_solve";
    private static final String LANGUAGE_FIELD = "language";
    private static final String CATEGORY_FIELD = "category";
    private static final String FIXER_FIELD = "fixer";
    private static final String EXAMPLE_FIELD = "example";
    private static final String PROFILES_FIELD = "profiles";

    private final Path path;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires the catalog to a file.
     *
     * @param path the JSON catalog to read
     */
    public JsonRuleCatalog(Path path) {
        this.path = path;
    }

    /**
     * Every rule the catalog file lists, skipping entries with no code.
     *
     * @return those rules, or none at all when the file cannot be read
     */
    @Override
    public List<SheriffRule> allRules() {
        try {
            JsonNode root = json.readTree(Files.readString(path));
            List<SheriffRule> rules = new ArrayList<>();
            for (JsonNode entry : root.path(RULES_FIELD)) {
                String code = entry.path(CODE_FIELD).asText();
                if (!code.isEmpty()) {
                    rules.add(toRule(entry, code));
                }
            }
            return rules;
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Turns one catalog entry into a rule, treating every missing field as
     * empty rather than as a failure.
     *
     * @param entry the catalog entry to convert
     * @param code the reference code already read from that entry
     * @return the rule it describes
     */
    private static SheriffRule toRule(JsonNode entry, String code) {
        List<String> profiles = new ArrayList<>();
        for (JsonNode profile : entry.path(PROFILES_FIELD)) {
            profiles.add(profile.asText());
        }
        return new SheriffRule(
                code,
                entry.path(DESCRIPTION_FIELD).asText(),
                entry.path(HOW_TO_SOLVE_FIELD).asText(),
                entry.path(LANGUAGE_FIELD).asText(),
                entry.path(CATEGORY_FIELD).asText(),
                entry.path(FIXER_FIELD).asText(),
                profiles,
                entry.path(EXAMPLE_FIELD).asText());
    }
}
