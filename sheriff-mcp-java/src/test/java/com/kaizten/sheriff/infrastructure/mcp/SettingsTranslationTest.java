package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kaizten.sheriff.infrastructure.config.Setting;
import com.kaizten.sheriff.infrastructure.config.Settings;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The settings page's translations: every section and setting of the
 * catalog is in each, and nothing else.
 */
class SettingsTranslationTest {

    @Test
    @DisplayName("every section and setting of the catalog has its words in Spanish, and no other is there")
    void spanishCoversTheCatalog() {
        SettingsTranslation spanish = SettingsPage.translations().get(0);
        Set<String> names = Settings.ALL.stream().map(Setting::name).collect(Collectors.toSet());
        Set<String> sections = Settings.ALL.stream().map(Setting::section).collect(Collectors.toSet());
        assertEquals("es", spanish.language());
        assertEquals(names, spanish.labels().keySet());
        assertEquals(names, spanish.helps().keySet());
        assertEquals(sections, spanish.sections().keySet());
        spanish.labels().values().forEach(label -> assertFalse(label.isBlank()));
        spanish.helps().values().forEach(help -> assertFalse(help.isBlank()));
    }

    @Test
    @DisplayName("a translation holds what it was given, by section and by setting")
    void aTranslationHoldsItsWords() {
        SettingsTranslation translation = new SettingsTranslation("xx", Map.of("Hooks", "Ganchos"),
                Map.of("A_FLAG", "Un interruptor"), Map.of("A_FLAG", "Lo enciende."));
        assertEquals("xx", translation.language());
        assertEquals("Ganchos", translation.sections().get("Hooks"));
        assertEquals("Un interruptor", translation.labels().get("A_FLAG"));
        assertEquals("Lo enciende.", translation.helps().get("A_FLAG"));
        assertEquals(null, translation.labels().get("B_FLAG"), "a setting it does not name has no words in it");
    }

    @Test
    @DisplayName("a translation is read section by section and setting by setting, past comments and blank lines")
    void aTranslationIsRead() {
        SettingsTranslation sample = SettingsTranslation.read("xx", "/settings-translation-sample.tsv");
        assertEquals("xx", sample.language());
        assertEquals(Map.of("Hooks", "Ganchos", "Tests", "Pruebas"), sample.sections());
        assertEquals(Map.of("A_FLAG", "Un interruptor", "B_SECONDS", "Unos segundos"), sample.labels());
        assertEquals(Map.of("A_FLAG", "Lo enciende.", "B_SECONDS", "Cuántos, con\ttabulador."), sample.helps());
        assertThrows(UnsupportedOperationException.class, () -> sample.labels().put("C", "c"),
                "what it read cannot be changed afterwards");
    }

    @Test
    @DisplayName("the page gives a section or a setting the words of each language that has them, and none of one "
            + "that lacks them")
    void eachLanguageGivesWhatItHas() {
        Setting flag = Settings.ALL.get(0);
        SettingsTranslation full = new SettingsTranslation("aa", Map.of("Sheriff", "Sheriff aa"),
                Map.of(flag.name(), "Etiqueta"), Map.of(flag.name(), "Ayuda."));
        SettingsTranslation onlyLabel = new SettingsTranslation("bb", Map.of(), Map.of(flag.name(), "Solo"),
                Map.of());
        SettingsTranslation onlyHelp = new SettingsTranslation("cc", Map.of(), Map.of(), Map.of(flag.name(),
                "Solo ayuda."));
        java.util.List<SettingsTranslation> all = java.util.List.of(full, onlyLabel, onlyHelp);

        assertEquals(Map.of("aa", "Sheriff aa"), SettingsPage.sectionTranslations("Sheriff", all));
        assertEquals(Map.of(), SettingsPage.sectionTranslations("Nowhere", all));
        assertEquals(Map.of("aa", Map.of("label", "Etiqueta", "help", "Ayuda.")),
                SettingsPage.settingTranslations(flag, all));
        assertEquals(Map.of(), SettingsPage.settingTranslations(flag, java.util.List.of()));
    }

    @Test
    @DisplayName("a translation that is missing, or a line that is neither a section nor a setting, says which")
    void aBadTranslationIsRefused() {
        assertEquals("The settings page's translation /missing.tsv is not in this jar.",
                assertThrows(IllegalStateException.class, () -> SettingsTranslation.read("xx", "/missing.tsv"))
                        .getMessage());
        assertEquals("Not a line of the settings page's translation: 'ONLY_A_NAME'.",
                assertThrows(IllegalStateException.class,
                        () -> SettingsTranslation.read("xx", "/settings-translation-bad.tsv")).getMessage());
    }
}
