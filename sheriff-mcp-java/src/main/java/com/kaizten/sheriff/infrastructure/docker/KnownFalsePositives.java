package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The findings Sheriff is known to get wrong, which no caller should ever see.
 *
 * <p>{@code FilenamePascalCase} on {@code package-info.java} and
 * {@code module-info.java}: names the Java language itself requires, so no
 * repair exists and every PetClinic session ended on the same five, with the
 * Stop hook blocking until its cap. Only those two files, never the rule: it
 * is right about every other file.
 *
 * <p>Applied once, in {@link SheriffDockerAnalyzer}, which every caller goes
 * through, so the MCP's tools, the hooks, the loop and the Maven plugin agree
 * on the count. No rule can be switched off by a project: an earlier version
 * let one exclude {@code SortedImport}, on the claim that PetClinic's
 * {@code spring-javaformat} rejects Sheriff's import order. Measured on
 * 1 October, it does not: with Sheriff's fixers applied, PetClinic's
 * {@code mvn test} passes, validation included.
 */
public final class KnownFalsePositives {

    private static final String FILE_NAME_RULE = "FilenamePascalCase";
    private static final String PACKAGE_INFO = "package-info.java";
    private static final String MODULE_INFO = "module-info.java";
    private static final Set<String> NAMES_JAVA_REQUIRES = Set.of(PACKAGE_INFO, MODULE_INFO);
    private static final String PATH_SEPARATOR = "/";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private KnownFalsePositives() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The findings that count.
     *
     * @param findings what Sheriff reported
     * @return the same, without the known false positives
     */
    public static List<SheriffFinding> without(List<SheriffFinding> findings) {
        List<SheriffFinding> kept = new ArrayList<>();
        for (SheriffFinding finding : findings) {
            if (!falsePositive(finding)) {
                kept.add(finding);
            }
        }
        return kept;
    }

    /**
     * Whether a finding is one Sheriff is known to get wrong.
     *
     * @param finding the finding
     * @return {@code true} for a file name the language requires
     */
    private static boolean falsePositive(SheriffFinding finding) {
        String file = finding.file();
        String name = file.substring(file.lastIndexOf(PATH_SEPARATOR) + 1);
        return FILE_NAME_RULE.equals(finding.referenceCode()) && NAMES_JAVA_REQUIRES.contains(name);
    }
}
