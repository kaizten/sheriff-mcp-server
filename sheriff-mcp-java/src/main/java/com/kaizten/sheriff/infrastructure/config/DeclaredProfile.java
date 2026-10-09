package com.kaizten.sheriff.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The rule profile a project declares for itself.
 *
 * <p>Going by the sources can only find a language, so it always gives the
 * base profile, {@code JAVA} for Java. A project built on a hexagonal or DDD
 * architecture wants {@code JAVA_HEXAGONAL} or {@code JAVA_DDD}, and that is
 * something only the project can say. It says it once, where every tool here
 * reads it: in Maven, the {@code sheriff.profile} property of its
 * {@code pom.xml}, the same one the Maven plugin takes; otherwise,
 * {@code profile} in a {@code .sheriff.properties} file.
 *
 * <p>Directories are searched nearest first, as Maven inherits properties
 * from the parent directory's {@code pom.xml}. The {@code pom.xml} chain
 * stops at the first directory without one, since a directory that is not a
 * Maven module inherits nothing. Every {@code pom.xml} is read before any
 * properties file: a Maven build resolves the property
 * through inheritance and never sees the file, so a tool that preferred the
 * file could analyze the same module under another profile than the build.
 */
public final class DeclaredProfile {

    /**
     * The file a project that does not build with Maven declares it in.
     */
    public static final String FILE_NAME = ".sheriff.properties";

    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final String FILE_KEY = "profile";
    private static final String MAVEN_BUILD = "pom.xml";
    private static final String PROPERTIES_ELEMENT = "properties";
    private static final String PROPERTY_ELEMENT = "sheriff.profile";
    private static final String UNRESOLVED_REFERENCE = "${";
    private static final String DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String NO_ACCESS = "";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private DeclaredProfile() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The profile declared in any of these directories.
     *
     * @param directories where to look, nearest first
     * @return the profile, or empty when none of them declares one
     */
    public static Optional<String> in(List<Path> directories) {
        for (Path directory : directories) {
            Path pom = directory.resolve(MAVEN_BUILD);
            if (!Files.isRegularFile(pom)) {
                break;
            }
            Optional<String> declared = fromPom(pom);
            if (declared.isPresent()) {
                return declared;
            }
        }
        for (Path directory : directories) {
            Optional<String> declared = fromFile(directory.resolve(FILE_NAME));
            if (declared.isPresent()) {
                return declared;
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code sheriff.profile} property of a {@code pom.xml}: only the
     * project's own {@code <properties>}, never one inside a Maven build
     * profile, which applies only when that build profile is active.
     *
     * @param pom the file
     * @return the value, or empty when the file, the property or a usable
     *     value is missing
     */
    private static Optional<String> fromPom(Path pom) {
        try (InputStream input = Files.newInputStream(pom)) {
            Element project = parser().parse(input).getDocumentElement();
            return child(project, PROPERTIES_ELEMENT)
                    .flatMap(properties -> child(properties, PROPERTY_ELEMENT))
                    .flatMap(property -> usable(property.getTextContent()));
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            return Optional.empty();
        }
    }

    /**
     * The {@code profile} key of a {@code .sheriff.properties} file.
     *
     * @param file the file
     * @return the value, or empty when the file or the key is missing
     */
    private static Optional<String> fromFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
            return usable(properties.getProperty(FILE_KEY));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * A value worth using: not blank, and not a Maven expression that only
     * Maven could resolve.
     *
     * @param value the value as written
     * @return it, trimmed, or empty
     */
    private static Optional<String> usable(String value) {
        if (value == null || value.isBlank() || value.contains(UNRESOLVED_REFERENCE)) {
            return Optional.empty();
        }
        return Optional.of(value.strip());
    }

    /**
     * The first child element with a name.
     *
     * @param parent where to look
     * @param name the element's name
     * @return that element, or empty
     */
    private static Optional<Element> child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    /**
     * A parser that reads a {@code pom.xml} and nothing it points to: no
     * document type, no external entities. It is silent: the JDK's parser
     * otherwise prints a broken file to stderr as {@code [Fatal Error]}, in
     * the system's language, which a hook or an install showed as if the
     * tool had failed; the exception it throws is enough.
     *
     * @return the parser
     * @throws ParserConfigurationException when the JDK cannot provide one
     */
    private static DocumentBuilder parser() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(DOCTYPE_FEATURE, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, NO_ACCESS);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, NO_ACCESS);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder;
    }
}
