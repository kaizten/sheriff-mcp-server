package com.kaizten.sheriff.infrastructure.config;

import com.kaizten.sheriff.infrastructure.coverage.CoverageThresholds;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The lines of a project's settings that check less than they did before: a
 * threshold lowered, prototype mode turned on, the tests taken
 * out of the end of a turn, a profile changed or removed.
 *
 * <p>A project's {@code .sheriff.properties}, and the {@code sheriff.*}
 * properties of its {@code pom.xml}, decide what the hooks hold an assistant
 * to, and the assistant can write both. A sentence of the instructions was all
 * that kept a model from turning a check off where it could not pass it. So
 * the Stop hook compares each such file with what it held when the turn began,
 * and this says which of its lines loosen a check. Each file is
 * compared on its own, a line it does not set taken at the setting's default:
 * what the file says, not what the layers add up to.
 *
 * <p>Only the settings that decide what is checked count, each in the
 * direction that checks less. A setting that cannot be ordered, the profile or
 * PIT's mutators, counts when it is changed or removed, never when it is
 * added. A value the setting does not take is read as not set, as the tools
 * ignore it.
 */
public final class LoosenedSettings {

    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final String MAVEN_BUILD = "pom.xml";
    private static final String NOTHING = "";
    private static final String PERCENT = "%";
    private static final String SPACE = " ";
    private static final String ON = "1";
    private static final String OFF = "0";
    private static final String APPLIED = "applied";
    private static final int NO_NUMBER = 0;
    private static final String PROFILE = "SHERIFF_PROFILE";
    private static final String SCOPE = "SHERIFF_SCOPE";
    private static final String TIMEOUT = "SHERIFF_TIMEOUT";
    private static final String STOP_MAX_BLOCKS = "SHERIFF_STOP_MAX_BLOCKS";
    private static final String STOP_MAX_STALLS = "SHERIFF_STOP_MAX_STALLS";
    private static final String PROTOTYPE = "SHERIFF_PROTOTYPE";
    private static final String STOP_RUNS_TESTS = "SHERIFF_STOP_RUNS_TESTS";
    private static final String COVERAGE = "SHERIFF_COVERAGE";
    private static final String BRANCHES = "SHERIFF_BRANCHES";
    private static final String MUTATION = "SHERIFF_MUTATION";
    private static final String MUTATORS = "SHERIFF_MUTATORS";
    private static final String COVERAGE_TIMEOUT = "SHERIFF_COVERAGE_TIMEOUT";
    private static final String MUTATION_TIMEOUT = "SHERIFF_MUTATION_TIMEOUT";
    private static final Map<String, String> LOOSE_VALUES = Map.of(PROTOTYPE, ON, STOP_RUNS_TESTS, OFF,
            SCOPE, APPLIED);
    private static final Set<String> LOWERED = Set.of(COVERAGE, BRANCHES, MUTATION, TIMEOUT, COVERAGE_TIMEOUT,
            MUTATION_TIMEOUT, STOP_MAX_BLOCKS, STOP_MAX_STALLS);
    private static final Set<String> CHANGED = Set.of(PROFILE, MUTATORS);
    private static final Map<String, String> FILE_KEYS = Map.of(DeclaredProfile.FILE_KEY, PROFILE,
            CoverageThresholds.LINES_KEY, COVERAGE, CoverageThresholds.BRANCHES_KEY, BRANCHES,
            CoverageThresholds.MUTANTS_KEY, MUTATION, CoverageThresholds.MUTATORS_KEY, MUTATORS);
    private static final Map<String, String> POM_PROPERTIES = Map.of(DeclaredProfile.PROPERTY_ELEMENT, PROFILE,
            CoverageThresholds.LINES_PROPERTY, COVERAGE, CoverageThresholds.BRANCHES_PROPERTY, BRANCHES,
            CoverageThresholds.MUTANTS_PROPERTY, MUTATION, CoverageThresholds.MUTATORS_PROPERTY, MUTATORS);

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private LoosenedSettings() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Whether a file is one a project declares these tools' settings in.
     *
     * @param file the file
     * @return {@code true} for a {@code .sheriff.properties} or a
     *     {@code pom.xml}
     */
    public static boolean holdsSettings(Path file) {
        Path name = file.getFileName();
        return name != null && (DeclaredProfile.FILE_NAME.equals(name.toString())
                || MAVEN_BUILD.equals(name.toString()));
    }

    /**
     * The lines of a settings file that check less now than before.
     *
     * @param file the file, a {@code .sheriff.properties} or a
     *     {@code pom.xml}
     * @param committed its text before, empty when it did not exist
     * @param current its text now, empty when it is gone
     * @return each line that checks less, in the order of its key
     */
    public static List<Loosening> between(Path file, String committed, String current) {
        boolean pom = MAVEN_BUILD.equals(String.valueOf(file.getFileName()));
        Map<String, String> before = pom ? DeclaredProfile.sheriffProperties(committed) : properties(committed);
        Map<String, String> after = pom ? DeclaredProfile.sheriffProperties(current) : properties(current);
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<Loosening> loosened = new ArrayList<>();
        for (String key : keys) {
            String was = before.getOrDefault(key, NOTHING);
            String now = after.getOrDefault(key, NOTHING);
            if (settingOf(key, pom).filter(setting -> loosens(setting, was, now)).isPresent()) {
                loosened.add(new Loosening(file, key, was, now));
            }
        }
        return loosened;
    }

    /**
     * The setting a key of a file stands for.
     *
     * @param key the key as written
     * @param pom whether the file is a {@code pom.xml}
     * @return the setting's name, or empty when the key is none these tools
     *     read there
     */
    private static Optional<String> settingOf(String key, boolean pom) {
        if (pom) {
            return Optional.ofNullable(POM_PROPERTIES.get(key));
        }
        return Settings.named(key).map(Setting::name).or(() -> Optional.ofNullable(FILE_KEYS.get(key)));
    }

    /**
     * Whether a setting checks less with one value than with another.
     *
     * @param setting the setting
     * @param before its value before, empty when not set
     * @param after its value now, empty when not set
     * @return {@code true} when the value now checks less
     */
    static boolean loosens(String setting, String before, String after) {
        String was = effective(setting, before);
        String now = effective(setting, after);
        if (LOOSE_VALUES.containsKey(setting)) {
            return LOOSE_VALUES.get(setting).equals(now) && !LOOSE_VALUES.get(setting).equals(was);
        }
        if (LOWERED.contains(setting)) {
            return number(setting, now) < number(setting, was);
        }
        return CHANGED.contains(setting) && !was.isEmpty() && !was.equals(now);
    }

    /**
     * A value as the tools take it: the setting's default when it is not set
     * or is not one the setting takes, without spaces.
     *
     * @param setting the setting
     * @param value the value as written, empty when not set
     * @return the value the tools use
     */
    private static String effective(String setting, String value) {
        String written = value.replace(SPACE, NOTHING).replace(PERCENT, NOTHING);
        Optional<Setting> known = Settings.named(setting);
        boolean usable = !written.isEmpty() && known.flatMap(found -> found.problemWith(written)).isEmpty();
        return usable ? written : known.map(Setting::defaultValue).orElse(NOTHING);
    }

    /**
     * A value as a number, or the setting's default when it is none.
     *
     * @param setting the setting
     * @param value the value
     * @return that number
     */
    private static long number(String setting, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            String fallback = Settings.named(setting).map(Setting::defaultValue).orElse(NOTHING);
            return fallback.equals(value) ? NO_NUMBER : number(setting, fallback);
        }
    }

    /**
     * The keys a {@code .sheriff.properties} text sets.
     *
     * @param text the text
     * @return each key's value, nothing when it cannot be read, as the tools
     *     then read none of it
     */
    private static Map<String, String> properties(String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException exception) {
            return Map.of();
        }
        Map<String, String> values = new TreeMap<>();
        properties.stringPropertyNames().forEach(key -> values.put(key, properties.getProperty(key).strip()));
        return values;
    }
}
