package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.valueobject.RuleSelection;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code sheriff_guidelines}: the rules themselves, before anything is
 * broken. Every rule a profile enforces, a search across the catalog, or one
 * rule in full with the code Sheriff's own fixer writes for it.
 *
 * <p>It runs no Sheriff and records no task: it only reads the catalog.
 */
final class SheriffGuidelinesTool {

    private static final String DESCRIPTION =
            "The rules, before writing: a one- or two-word query to search them, or a rule id for "
            + "one in full.";
    private static final String TITLE = "Look up code standards rules";
    private static final ToolAnnotations ANNOTATIONS = ToolAnnotations.readingOnly(TITLE);
    private static final String QUERY_ARGUMENT = "query";
    private static final String QUERY_HELP = "Search text, matched against rule ids, descriptions and categories.";
    private static final String REFERENCE_CODE_HELP =
            "A single rule id, for that rule's full text and accepted code.";
    private static final int FULL_DETAIL_LIMIT = 60;
    private static final String NO_CATALOG =
            "No rule catalog is available, so the rules can only be read off findings as they "
            + "happen. This server extracts one from the Sheriff image by itself, and that did not "
            + "work: its log says why, usually Docker not running. Or point SHERIFF_RULES_CATALOG "
            + "at a rules_catalog.json.";
    private static final String NO_SUCH_RULE = "No rule with id '%s'. Search instead: %s with query='%s'.";
    private static final String MATCHING = "%d of %d rule(s) matching '%s'";
    private static final String ENFORCES = "The %d rule(s) the '%s' profile enforces";
    private static final String APPROXIMATE = "The catalog does not record which rules the '%s' profile runs, "
            + "so these are all %d rule(s) of its language: some of them may not apply to it";
    private static final String NOTHING_MATCHED =
            "Nothing matched '%s'. The catalog has %d rules, searched word by word, so every word "
            + "has to appear somewhere in a rule. Try fewer words, or one of the terms the rules "
            + "themselves use: javadoc, comment, import, naming, hardcoded, valueobject, test.";
    private static final String NAME_ONLY_RULE = "- %s: %s";
    private static final String TRUNCATION_NOTE =
            "%nListed by name only -- %d rules is too many to spell out. Ask again with a query, "
            + "or with reference_code, for the full text of one and the code Sheriff accepts for it.";
    private static final String RULE_HEADING = "## %s";
    private static final String NO_DESCRIPTION = "(no description)";
    private static final String HOW_TO_SOLVE = "How to solve: %s";
    private static final String PROFILES_LINE = "Profiles that run it: %s";
    private static final String PROFILE_SEPARATOR = ", ";
    private static final String HAS_FIXER_LINE = "Sheriff can repair this one itself (%s).";
    private static final String EXAMPLE_HEADING = "How Sheriff's own fixer writes the accepted code (read the f-string for the shape):";
    private static final String CODE_FENCE_OPEN = "```python";
    private static final String CODE_FENCE_CLOSE = "```";
    private static final String NEWLINE = "\n";

    private final ToolContext context;

    /**
     * Wires the tool to what it shares with the others.
     *
     * @param context the catalog and the configuration
     */
    SheriffGuidelinesTool(ToolContext context) {
        this.context = context;
    }

    /**
     * The tool's definition, bound to its handler.
     *
     * @return that tool
     */
    Tool definition() {
        Map<String, Object> schema = Schemas.objectSchema(Map.of(
                QUERY_ARGUMENT, Schemas.stringProperty(QUERY_HELP),
                ToolContext.REFERENCE_CODE_ARGUMENT, Schemas.stringProperty(REFERENCE_CODE_HELP),
                ToolContext.PROFILE_ARGUMENT, Schemas.stringProperty(ToolContext.PROFILE_HELP)));
        return new Tool(context.guidelinesTool(), DESCRIPTION, schema, ANNOTATIONS, this::handle);
    }

    /**
     * Says what a profile expects, or answers a search across the whole
     * catalog.
     *
     * @param arguments the call's arguments
     * @return the text to answer with
     */
    private String handle(Map<String, Object> arguments) {
        String query = ToolContext.textArgument(arguments, QUERY_ARGUMENT);
        String referenceCode = ToolContext.textArgument(arguments, ToolContext.REFERENCE_CODE_ARGUMENT);
        String profile = context.profileArgument(arguments, context.config().defaultComponent());
        List<SheriffRule> rules = context.catalog().allRules();
        if (rules.isEmpty()) {
            return NO_CATALOG;
        }
        if (!referenceCode.isEmpty()) {
            Optional<SheriffRule> rule = Rules.findRule(rules, referenceCode);
            if (rule.isEmpty()) {
                return String.format(NO_SUCH_RULE, referenceCode, context.guidelinesTool(), referenceCode);
            }
            return renderRule(rule.get(), true);
        }
        List<SheriffRule> selected;
        String heading;
        if (!query.isEmpty()) {
            selected = Rules.searchRules(rules, query);
            heading = String.format(MATCHING, selected.size(), rules.size(), query);
        } else {
            RuleSelection selection = Rules.selectRulesForProfile(rules, profile);
            selected = selection.rules();
            heading = selection.exact()
                    ? String.format(ENFORCES, selected.size(), profile)
                    : String.format(APPROXIMATE, profile, selected.size());
        }
        if (selected.isEmpty()) {
            return String.format(NOTHING_MATCHED, query, rules.size());
        }
        boolean detailed = selected.size() <= FULL_DETAIL_LIMIT;
        List<String> lines = new ArrayList<>(List.of(heading, ToolContext.EMPTY));
        for (SheriffRule rule : selected) {
            if (detailed) {
                lines.add(renderRule(rule, !query.isEmpty()));
                lines.add(ToolContext.EMPTY);
            } else {
                lines.add(String.format(NAME_ONLY_RULE, rule.code(), rule.description()));
            }
        }
        if (!detailed) {
            lines.add(String.format(TRUNCATION_NOTE, selected.size()));
        }
        String joined = String.join(NEWLINE, lines);
        return joined.stripTrailing();
    }

    /**
     * One rule's full text, the way this tool presents it.
     *
     * @param rule the rule to render
     * @param withExample whether to include the fixer's own code, when it
     *     has one
     * @return the rendered text
     */
    private String renderRule(SheriffRule rule, boolean withExample) {
        List<String> lines = new ArrayList<>(List.of(
                String.format(RULE_HEADING, rule.code()),
                ToolContext.EMPTY,
                rule.description().isEmpty() ? NO_DESCRIPTION : rule.description()));
        if (!rule.howToSolve().isEmpty()) {
            lines.add(ToolContext.EMPTY);
            lines.add(String.format(HOW_TO_SOLVE, rule.howToSolve()));
        }
        if (!rule.profiles().isEmpty()) {
            lines.add(ToolContext.EMPTY);
            lines.add(String.format(PROFILES_LINE, String.join(PROFILE_SEPARATOR, rule.profiles())));
        }
        if (rule.hasFixer()) {
            lines.add(ToolContext.EMPTY);
            lines.add(String.format(HAS_FIXER_LINE, context.fixTool()));
        }
        if (withExample && rule.hasExample()) {
            lines.add(ToolContext.EMPTY);
            lines.add(EXAMPLE_HEADING);
            lines.add(ToolContext.EMPTY);
            lines.add(CODE_FENCE_OPEN);
            lines.add(rule.example().stripTrailing());
            lines.add(CODE_FENCE_CLOSE);
        }
        return String.join(NEWLINE, lines);
    }
}
