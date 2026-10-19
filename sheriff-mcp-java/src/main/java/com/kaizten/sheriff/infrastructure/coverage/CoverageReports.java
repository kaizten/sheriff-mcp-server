package com.kaizten.sheriff.infrastructure.coverage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * What the XML reports of JaCoCo and PIT say about each source file, named
 * as a path under the source folder ({@code demo/Calc.java}), which is how
 * both tools can be matched with what git says changed.
 */
final class CoverageReports {

    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final String PACKAGE = "package";
    private static final String SOURCE_FILE = "sourcefile";
    private static final String LINE = "line";
    private static final String NAME = "name";
    private static final String NUMBER = "nr";
    private static final String MISSED_INSTRUCTIONS = "mi";
    private static final String COVERED_INSTRUCTIONS = "ci";
    private static final String MISSED_BRANCHES = "mb";
    private static final String COVERED_BRANCHES = "cb";
    private static final String MUTATION = "mutation";
    private static final String DETECTED = "detected";
    private static final String STATUS = "status";
    private static final String MUTANT_SOURCE_FILE = "sourceFile";
    private static final String MUTATED_CLASS = "mutatedClass";
    private static final String LINE_NUMBER = "lineNumber";
    private static final String DESCRIPTION = "description";
    private static final String TRUE = "true";
    private static final String SLASH = "/";
    private static final String DOT = ".";
    private static final String NOTHING = "";
    private static final int NONE = 0;
    private static final int START = 0;
    private static final int NOT_FOUND = -1;
    private static final String PLUGIN = "plugin";
    private static final String ARTIFACT_ID = "artifactId";
    private static final String SUREFIRE = "maven-surefire-plugin";
    private static final String CONFIGURATION = "configuration";
    private static final String SYSTEM_PROPERTY_VARIABLES = "systemPropertyVariables";
    private static final String UNRESOLVED = "${";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String NO_ACCESS = "";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private CoverageReports() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The lines of code JaCoCo found in each source file, whether a test ran
     * each one and which of its branches the tests took. A line with no
     * instructions, a comment or a brace, is not there.
     *
     * @param report JaCoCo's {@code jacoco.xml}
     * @return each file's lines, by number
     * @throws IOException when the report cannot be read
     */
    static Map<String, Map<Integer, CoveredLine>> lines(Path report) throws IOException {
        Map<String, Map<Integer, CoveredLine>> files = new TreeMap<>();
        NodeList packages = parse(report).getElementsByTagName(PACKAGE);
        for (int index = NONE; index < packages.getLength(); index++) {
            Element folder = (Element) packages.item(index);
            for (Element source : children(folder, SOURCE_FILE)) {
                Map<Integer, CoveredLine> lines = new TreeMap<>();
                for (Element line : children(source, LINE)) {
                    int missed = number(line.getAttribute(MISSED_INSTRUCTIONS));
                    int covered = number(line.getAttribute(COVERED_INSTRUCTIONS));
                    int coveredBranches = number(line.getAttribute(COVERED_BRANCHES));
                    int branches = number(line.getAttribute(MISSED_BRANCHES)) + coveredBranches;
                    if (missed + covered > NONE) {
                        lines.put(number(line.getAttribute(NUMBER)),
                                new CoveredLine(covered > NONE, branches, coveredBranches));
                    }
                }
                files.put(inFolder(folder.getAttribute(NAME), source.getAttribute(NAME)), lines);
            }
        }
        return files;
    }

    /**
     * The mutants PIT made, with what became of each.
     *
     * @param report PIT's {@code mutations.xml}
     * @return the mutants
     * @throws IOException when the report cannot be read
     */
    static List<Mutant> mutants(Path report) throws IOException {
        List<Mutant> mutants = new ArrayList<>();
        NodeList found = parse(report).getElementsByTagName(MUTATION);
        for (int index = NONE; index < found.getLength(); index++) {
            Element mutation = (Element) found.item(index);
            String mutated = text(mutation, MUTATED_CLASS);
            int dot = mutated.lastIndexOf(DOT);
            String folder = dot == NOT_FOUND ? NOTHING : mutated.substring(START, dot).replace(DOT, SLASH);
            mutants.add(new Mutant(inFolder(folder, text(mutation, MUTANT_SOURCE_FILE)),
                    number(text(mutation, LINE_NUMBER)), mutation.getAttribute(STATUS),
                    text(mutation, DESCRIPTION), TRUE.equals(mutation.getAttribute(DETECTED))));
        }
        return mutants;
    }

    /**
     * The system properties surefire gives the tests, as an effective
     * {@code pom.xml} has them, inheritance and properties resolved.
     *
     * @param effectivePom what {@code help:effective-pom} wrote
     * @return each property's value by its name, those Maven could not
     *     resolve left out
     * @throws IOException when the file cannot be read
     */
    static Map<String, String> surefireProperties(Path effectivePom) throws IOException {
        Map<String, String> properties = new TreeMap<>();
        NodeList plugins = parse(effectivePom).getElementsByTagName(PLUGIN);
        for (int index = NONE; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            if (!SUREFIRE.equals(text(plugin, ARTIFACT_ID))) {
                continue;
            }
            for (Element configuration : children(plugin, CONFIGURATION)) {
                for (Element variables : children(configuration, SYSTEM_PROPERTY_VARIABLES)) {
                    addResolved(properties, variables);
                }
            }
        }
        return properties;
    }

    /**
     * Adds each property an element lists, unless its value is still a Maven
     * expression.
     *
     * @param properties where they go
     * @param variables the element whose children are the properties
     */
    private static void addResolved(Map<String, String> properties, Element variables) {
        for (Node node = variables.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element property && !property.getTextContent().contains(UNRESOLVED)) {
                properties.put(property.getTagName(), property.getTextContent().strip());
            }
        }
    }

    /**
     * A source file named under its source folder.
     *
     * @param folder its package as a folder, {@code demo}, empty for none
     * @param file its name
     * @return {@code demo/Calc.java}
     */
    private static String inFolder(String folder, String file) {
        return folder.isEmpty() ? file : folder + SLASH + file;
    }

    /**
     * The child elements of an element with a name.
     *
     * @param parent the element
     * @param name their name
     * @return them, in order
     */
    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                found.add(element);
            }
        }
        return found;
    }

    /**
     * The text of an element's first child with a name.
     *
     * @param parent the element
     * @param name the child's name
     * @return its text, or the empty string when there is none
     */
    private static String text(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? NOTHING : found.get(START).getTextContent().strip();
    }

    /**
     * A whole number as a report writes it.
     *
     * @param text the text
     * @return the number, or 0 when it is none
     */
    private static int number(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException exception) {
            return NONE;
        }
    }

    /**
     * Reads a report, without fetching the document type it names: JaCoCo's
     * names {@code report.dtd}, which is not beside it.
     *
     * @param report the report
     * @return its root element
     * @throws IOException when it cannot be read or is not XML
     */
    private static Element parse(Path report) throws IOException {
        try (InputStream input = Files.newInputStream(report)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, NO_ACCESS);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, NO_ACCESS);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(input).getDocumentElement();
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }
}
