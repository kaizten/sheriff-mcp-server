package com.kaizten.sheriff.maven;

import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.infrastructure.catalog.CatalogProvisioning;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.config.DeclaredProfile;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * What both goals share: the parameters, and turning the module Maven is
 * building into the agent's own configuration.
 *
 * <p>The agent reads its configuration from the environment, because a
 * command line launched by hand has nowhere else to get it. A build does:
 * Maven already knows which directory the module is, what it is called and
 * whether it has sources at all, so the values the CLI has to be told are
 * derived here instead of configured.
 */
public abstract class SheriffMojo extends AbstractMojo {

    /**
     * What a goal says when Sheriff's image could not be had and the reason
     * is unknown.
     */
    private static final String NO_IMAGE = "Sheriff's image is not available on this machine.";

    /**
     * The variable the agent reads the mounted directory from.
     */
    private static final String TARGET_REPOSITORY = "TARGET_REPO";

    /**
     * The variable the agent reads the analyzed component from.
     */
    private static final String COMPONENT = "SHERIFF_COMPONENT";

    /**
     * The variable the agent reads the rule profile from.
     */
    private static final String TEST_TYPE = "SHERIFF_TEST_TYPE";

    /**
     * The variable the agent reads the image from.
     */
    private static final String IMAGE = "SHERIFF_IMAGE";

    /**
     * The variable the agent reads the per-run timeout from.
     */
    private static final String TIMEOUT = "SHERIFF_TIMEOUT";

    /**
     * The profile applied when neither the build nor the project names one,
     * the same as the MCP server's default for Java.
     */
    private static final String DEFAULT_PROFILE = "JAVA";

    /**
     * The file a directory is recognized as a Maven project by.
     */
    private static final String MAVEN_BUILD = "pom.xml";

    /**
     * The image used when nothing says otherwise. Mirrors the annotation.
     */
    private static final String DEFAULT_IMAGE = "kaizten/sheriff:latest";

    /**
     * The per-run timeout used when nothing says otherwise. Mirrors the annotation.
     */
    private static final int DEFAULT_TIMEOUT = 300;

    /**
     * The directory whose presence means the module has code to analyze.
     */
    private static final String SOURCES = "src";

    /**
     * The variable the agent reads the export directory from.
     */
    private static final String EXPORT_DIRECTORY = "SHERIFF_EXPORT_DIR";

    /**
     * Where Sheriff's findings and tracked files are copied, under the
     * module's build directory, beside the unpacked catalog.
     */
    private static final String EXPORT_FOLDER = "sheriff";

    /**
     * The variable the agent finds the shared cache through, where a catalog
     * for a newer image than the bundled one is extracted.
     */
    private static final String CACHE_HOME = "XDG_CACHE_HOME";

    /**
     * What {@link #CACHE_HOME} is taken as when this machine does not set
     * it: the user's {@code .cache}.
     */
    private static final String DEFAULT_CACHE_HOME = "";

    /**
     * The catalog, carried inside this plugin's own jar. It is copied there
     * at build time from the agent module, so it is generated rather than a
     * fourth committed copy that could drift from the other two.
     */
    private static final String CATALOG_RESOURCE = "/rules_catalog.json";

    /**
     * Where the catalog is unpacked, under the module's build directory, so
     * nothing this plugin needs is ever written into the analyzed sources.
     */
    private static final String CATALOG_FILE = "sheriff/rules_catalog.json";

    /**
     * The module being built.
     */
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    protected File basedir;

    /**
     * The rule profile. {@code JAVA} is style and documentation;
     * {@code JAVA_HEXAGONAL} and {@code JAVA_DDD*} also check architecture and
     * are wrong for a module that never claimed to have it. Set as the
     * {@code sheriff.profile} property of the {@code pom.xml}, which is what
     * the MCP server and the hooks read too; left unset, see
     * {@link #profile()}.
     */
    @Parameter(property = "sheriff.profile")
    protected String profile;

    /**
     * The Sheriff image to run.
     */
    @Parameter(property = "sheriff.image", defaultValue = "kaizten/sheriff:latest")
    protected String image = DEFAULT_IMAGE;

    /**
     * Seconds allowed for one Sheriff run.
     */
    @Parameter(property = "sheriff.timeout", defaultValue = "300")
    protected int timeoutSeconds = DEFAULT_TIMEOUT;

    /**
     * Whether to do nothing at all.
     */
    @Parameter(property = "sheriff.skip", defaultValue = "false")
    protected boolean skip;

    /**
     * The module's build directory, which is where anything this plugin
     * needs to write goes.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    protected File buildDirectory;

    /**
     * Whether this module has anything for Sheriff to look at.
     *
     * <p>An aggregator module is a {@code pom.xml} and nothing else, and
     * analyzing it reports zero errors for the honest reason that there was
     * no code — which reads exactly like a module that passed. Skipping it
     * says which of the two happened.
     *
     * @return whether the module has a sources directory
     */
    protected boolean hasSources() {
        return new File(basedir, SOURCES).isDirectory();
    }

    /**
     * The module's directory, absolute, as everything here measures from it.
     *
     * @return that directory
     */
    protected File moduleDirectory() {
        return basedir.getAbsoluteFile();
    }

    /**
     * The agent's composition, wired for this module.
     *
     * <p>Sheriff mounts a directory and analyzes a subdirectory of it, so a
     * Maven module is always "my parent, and me inside it". That is the whole
     * derivation, and it is exact rather than a guess, which is the reason
     * this goal needs no configuration where the command line needs three
     * variables.
     *
     * @return that composition
     */
    protected Composition composition() {
        return Composition.real(new Configuration(environment(), agentDirectory()));
    }

    /**
     * The agent's environment for this module, as this goal derives it.
     *
     * @return the variables the agent would otherwise have been given by hand
     */
    protected Map<String, String> environment() {
        File module = moduleDirectory();
        Map<String, String> environment = new HashMap<>();
        String mounted = module.getParent();
        environment.put(TARGET_REPOSITORY, mounted == null ? module.getPath() : mounted);
        environment.put(COMPONENT, module.getName());
        environment.put(TEST_TYPE, profile());
        environment.put(IMAGE, image);
        environment.put(TIMEOUT, String.valueOf(timeoutSeconds));
        if (buildDirectory != null) {
            environment.put(EXPORT_DIRECTORY, buildDirectory.toPath().resolve(EXPORT_FOLDER).toString());
        }
        unpackedCatalog();
        String cacheHome = Objects.requireNonNullElse(System.getenv(CACHE_HOME), DEFAULT_CACHE_HOME);
        environment.put(CACHE_HOME, CatalogProvisioning.cacheHome(cacheHome).toString());
        return environment;
    }

    /**
     * The rule catalog, unpacked from this plugin's jar into the build
     * directory.
     *
     * <p>The repair goal needs a catalog: which rules Sheriff can fix by
     * itself is read from it. This one is only the first choice. It lands
     * where the agent looks for its own, and the agent uses it when it
     * matches the image installed; when the image is newer than this plugin,
     * the agent extracts one into the shared cache instead. The analysis does
     * not need it, so a catalog that cannot be unpacked is not a failure here.
     *
     * <p>Unpacked on every run rather than once: a copy left in
     * {@code target/} by an older plugin would otherwise outlive the upgrade
     * that brought a newer catalog, until somebody ran {@code mvn clean}.
     *
     * @return where it was written, or {@code null} when it could not be
     *     unpacked
     */
    protected Path unpackedCatalog() {
        if (buildDirectory == null) {
            return null;
        }
        Path target = buildDirectory.toPath().resolve(CATALOG_FILE);
        try (InputStream source = SheriffMojo.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (source == null) {
                return Files.isRegularFile(target) ? target : null;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException exception) {
            getLog().debug("Could not unpack the rule catalog: " + exception.getMessage());
            return Files.isRegularFile(target) ? target : null;
        }
    }

    /**
     * The profiles this module is analyzed under: the one the build names,
     * else the one a {@code .sheriff.properties} declares beside the module
     * or in the aggregator above it, else {@code JAVA}; an architecture
     * profile comes with its language's base profile. The file is how a
     * project says it once for every tool, and the MCP server and the hooks
     * read it the same way.
     *
     * @return the profiles, comma-separated
     */
    protected String profile() {
        return Rules.withBaseProfiles(chosenProfile());
    }

    /**
     * The profile chosen for this module, before the base profile of its
     * language is added: an architecture profile such as
     * {@code JAVA_HEXAGONAL} does not include the {@code JAVA} rules, so it is
     * always run beside them.
     *
     * @return the profile, or list of them
     */
    private String chosenProfile() {
        if (profile != null && !profile.isBlank()) {
            return profile.strip();
        }
        Path module = moduleDirectory().toPath();
        List<Path> directories = new ArrayList<>(List.of(module));
        Path parent = module.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve(MAVEN_BUILD))) {
            directories.add(parent);
        }
        return DeclaredProfile.in(directories).orElse(DEFAULT_PROFILE);
    }

    /**
     * Where this plugin keeps its own files, {@code target/sheriff}, beside
     * Sheriff's exports and the unpacked catalog: never the analyzed
     * module's sources.
     *
     * @return that directory
     */
    protected Path agentDirectory() {
        return buildDirectory == null ? moduleDirectory().toPath() : buildDirectory.toPath().resolve(EXPORT_FOLDER);
    }

    /**
     * Whether this goal should do nothing, saying why when so.
     *
     * @return whether to return without running Sheriff
     */
    protected boolean skipped() {
        if (skip) {
            getLog().info("Sheriff skipped (sheriff.skip).");
            return true;
        }
        if (!hasSources()) {
            getLog().info(String.format("Sheriff skipped: %s has no %s directory.", basedir.getName(), SOURCES));
            return true;
        }
        return false;
    }

    /**
     * Makes sure Sheriff's image is here before a goal runs it: pulled when
     * missing, with its own time limit rather than inside the analysis's,
     * and reported when a newer one is published. Overridable so the goals
     * can be tested without Docker.
     *
     * @throws MojoExecutionException when the image is missing and could not
     *     be pulled
     */
    protected void ensureImage() throws MojoExecutionException {
        ImageProvisioning image = composition().imageProvisioning(System.err);
        if (!image.now()) {
            throw new MojoExecutionException(image.blocker().orElse(NO_IMAGE));
        }
    }

    /**
     * One analysis of this module, which also leaves Sheriff's findings and
     * tracked files in {@code target/sheriff/}. Overridable so the decisions
     * the goals take can be tested without Docker.
     *
     * @return what Sheriff said
     */
    protected AnalysisResult analyze() {
        return composition().analyzer().analyze();
    }
}
