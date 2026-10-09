package com.kaizten.sheriff.infrastructure.extractor;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges everything the image was asked into one catalog.
 *
 * <p>A reference code that {@code fix -l} knows but no message class describes
 * still makes the list, with empty text. What Sheriff can flag is worth having
 * complete, and dropping such a rule silently would be indistinguishable from
 * the rule not existing.
 */
public final class CatalogBuilder {

    private static final String GENERAL = "general";
    private static final String EMPTY = "";
    private static final String NOT_INSTANTIABLE = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses instantiation: this is a holder of static methods.
     *
     * <p>Declared private and still throwing, so that reflection cannot make an
     * instance of it either.
     */
    private CatalogBuilder() {
        throw new UnsupportedOperationException(NOT_INSTANTIABLE);
    }

    /**
     * Builds the whole catalog.
     *
     * @param constantsDump the javap dump of the classes carrying rule text
     * @param fixerOutput what {@code fix -l} printed
     * @param factoryDump the factory bytecode, empty to skip profile mapping
     * @param graphDump the reference graph, empty to skip profile mapping
     * @param treeJson Sheriff's own exported tree, empty for an image without it
     * @param profileNames the valid test names, empty for an image without them
     * @param fixerScripts every fixer script, empty to carry no examples
     * @param messageLinksDump the checkers disassembled, empty for an image
     *     whose message classes carry their own text
     * @return every rule, sorted by language and code, with profiles attached
     */
    public static List<CatalogEntry> build(
            String constantsDump,
            String fixerOutput,
            String factoryDump,
            String graphDump,
            String treeJson,
            List<String> profileNames,
            String fixerScripts,
            String messageLinksDump) {
        Map<String, CatalogEntry> byCode = new LinkedHashMap<>(ConstantsParser.messageClasses(constantsDump));
        applyMessageLinks(byCode, messageLinksDump);
        Map<String, String> fixers = FixerListParser.parse(fixerOutput);
        Map<String, String> examples = FixerExampleParser.parse(fixerScripts);
        for (Map.Entry<String, String> fixer : fixers.entrySet()) {
            byCode.computeIfAbsent(fixer.getKey(), code -> new CatalogEntry(
                    new SheriffRule(code, "", "", GENERAL, "", "", List.of()),
                    code, CatalogEntry.MESSAGE_CLASS_SOURCE, ""));
        }
        List<CatalogEntry> entries = new ArrayList<>();
        Set<String> knownDescriptions = new LinkedHashSet<>();
        for (CatalogEntry entry : byCode.values()) {
            CatalogEntry withFixer = entry.withFixer(fixers.getOrDefault(entry.referenceCode(), EMPTY));
            entries.add(withFixer.withExample(examples.getOrDefault(withFixer.rule().fixer(), EMPTY)));
            if (!entry.rule().description().isEmpty()) {
                knownDescriptions.add(entry.rule().description());
            }
        }
        entries.sort(Comparator.comparing(CatalogEntry::code));
        entries.addAll(ConstantsParser.checkerClasses(constantsDump, knownDescriptions));
        entries.sort(Comparator.comparing((CatalogEntry entry) -> entry.rule().language())
                .thenComparing(CatalogEntry::code));
        return attachProfiles(entries, factoryDump, graphDump, treeJson, profileNames);
    }

    /**
     * Fills in the text of the codes whose message class does not carry any.
     *
     * <p>Doing this here rather than treating the pairings as separate rules
     * is what keeps one rule from appearing twice: once as a code with no
     * message and once as a message with no code.
     *
     * @param byCode the rules read from the message classes, edited in place
     * @param messageLinksDump the checkers disassembled, empty to do nothing
     */
    private static void applyMessageLinks(Map<String, CatalogEntry> byCode, String messageLinksDump) {
        if (messageLinksDump.isEmpty()) {
            return;
        }
        for (Map.Entry<String, CatalogEntry> link : MessageLinkParser.parse(messageLinksDump).entrySet()) {
            CatalogEntry existing = byCode.get(link.getKey());
            if (existing == null) {
                byCode.put(link.getKey(), link.getValue());
                continue;
            }
            byCode.put(link.getKey(), merged(existing, link.getValue()));
        }
    }

    /**
     * One rule with the gaps its message class left filled in from the
     * checker, and everything it already knew kept.
     *
     * <p>The owner stays the message class rather than becoming the checker.
     * It is the rule's own identity, it is what the earlier catalogs recorded,
     * and the profile mapping reaches either of them from the same root.
     *
     * @param existing the rule as the message class gave it
     * @param link the same rule as the checker call gave it
     * @return the two merged
     */
    private static CatalogEntry merged(CatalogEntry existing, CatalogEntry link) {
        SheriffRule known = existing.rule();
        SheriffRule found = link.rule();
        return new CatalogEntry(
                new SheriffRule(
                        known.code(),
                        known.description().isEmpty() ? found.description() : known.description(),
                        known.howToSolve().isEmpty() ? found.howToSolve() : known.howToSolve(),
                        known.language().isEmpty() || GENERAL.equals(known.language())
                                ? found.language() : known.language(),
                        known.category().isEmpty() ? found.category() : known.category(),
                        known.fixer(),
                        known.profiles(),
                        known.example()),
                existing.referenceCode(),
                existing.source(),
                existing.owner().isEmpty() ? link.owner() : existing.owner());
    }

    /**
     * Which profiles run which rules, folded back into the entries.
     *
     * <p>Sheriff's own tree wins when the image offers it. The bytecode
     * reading is the fallback, and is an approximation: it is what static
     * references can say, not what the tool states.
     *
     * @param entries the merged rules
     * @param factoryDump the factory bytecode
     * @param graphDump the reference graph
     * @param treeJson Sheriff's own exported tree
     * @param profileNames the valid test names
     * @return the same rules, each carrying its profiles
     */
    static List<CatalogEntry> attachProfiles(
            List<CatalogEntry> entries,
            String factoryDump,
            String graphDump,
            String treeJson,
            List<String> profileNames) {
        Map<String, List<String>> derived = factoryDump.isEmpty() || graphDump.isEmpty()
                ? Map.of()
                : ProfileMapper.profileRules(entries, factoryDump, graphDump);
        Map<String, List<String>> byProfile;
        if (!treeJson.isEmpty() && !profileNames.isEmpty()) {
            byProfile = mergeProfileMaps(
                    TestTreeMapper.profileRules(entries, treeJson, profileNames), derived);
        } else if (!derived.isEmpty()) {
            byProfile = derived;
        } else {
            return entries;
        }
        Map<String, List<String>> byCode = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> profile : byProfile.entrySet()) {
            for (String code : profile.getValue()) {
                byCode.computeIfAbsent(code, key -> new ArrayList<>()).add(profile.getKey());
            }
        }
        List<CatalogEntry> withProfiles = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            List<String> profiles = new ArrayList<>(byCode.getOrDefault(entry.code(), List.of()));
            profiles.sort(String::compareTo);
            withProfiles.add(entry.withProfiles(profiles));
        }
        return withProfiles;
    }

    /**
     * The tree's answer, with the bytecode's for codes the tree never names.
     *
     * <p>Not a union, and not the tree alone. Where the two agree on a name
     * the tree is the better answer — it is what Sheriff says rather than
     * what static references imply, and it correctly narrows a profile the
     * bytecode over-reached. But a whole family of codes has no leaf
     * resembling it: {@code JavaValueObjectShouldKeepHashCodeUnchanged} and
     * its nineteen siblings are all the single leaf "Java value object
     * test". Taking the tree alone dropped 105 codes out of every profile,
     * including rules that demonstrably fire.
     *
     * @param fromTree what Sheriff's own tree said
     * @param fromBytecode what the reference graph could reach
     * @return the tree's answer, extended for the codes it never names
     */
    static Map<String, List<String>> mergeProfileMaps(
            Map<String, List<String>> fromTree, Map<String, List<String>> fromBytecode) {
        Set<String> placed = new LinkedHashSet<>();
        fromTree.values().forEach(placed::addAll);
        Map<String, List<String>> merged = new java.util.TreeMap<>(fromTree);
        for (Map.Entry<String, List<String>> profile : fromBytecode.entrySet()) {
            List<String> recovered = profile.getValue().stream()
                    .filter(code -> !placed.contains(code))
                    .toList();
            if (recovered.isEmpty()) {
                continue;
            }
            Set<String> codes = new LinkedHashSet<>(merged.getOrDefault(profile.getKey(), List.of()));
            codes.addAll(recovered);
            List<String> sorted = new ArrayList<>(codes);
            sorted.sort(String::compareTo);
            merged.put(profile.getKey(), sorted);
        }
        return merged;
    }

    /**
     * How many rules each profile runs, for the catalog's summary.
     *
     * @param entries the finished catalog
     * @return profile names and their rule counts
     */
    public static Map<String, Integer> profileCounts(List<CatalogEntry> entries) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (CatalogEntry entry : entries) {
            for (String profile : entry.rule().profiles()) {
                counts.merge(profile, 1, Integer::sum);
            }
        }
        return counts;
    }
}
