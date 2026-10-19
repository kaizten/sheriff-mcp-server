package com.kaizten.sheriff.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One setting: how a line of the catalog reads as one, and which values it
 * takes, with the reason it gives for one it does not.
 */
class SettingTests {

    @Test
    @DisplayName("a line of the catalog gives the name, the kind with its minimum or choices, the default, who may "
            + "set it and its help")
    void aLineOfTheCatalogIsASetting() {
        assertEquals(new Setting("A_COUNT", "5", SettingKind.COUNT, 3, List.of(), true, "Help."),
                Setting.parse("A_COUNT\tcount:3\t5\tproject\tHelp."));
        assertEquals(new Setting("A_CHOICE", "x", SettingKind.CHOICE, 0, List.of("x", "y"), false, "Pick."),
                Setting.parse("A_CHOICE\tchoice:x,y\tx\tuser\tPick."));
        assertEquals(new Setting("A_WORD", "", SettingKind.WORD, 0, List.of(), true, "Worked out."),
                Setting.parse("A_WORD\tword\t\tproject\tWorked out."));
    }

    @Test
    @DisplayName("a line with a field missing is no setting, and says which line")
    void aShortLineIsRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Setting.parse("A_WORD\tword\tdefault"));
        assertEquals("Not a line of the settings catalog: 'A_WORD\tword\tdefault'.", refused.getMessage());
    }

    @Test
    @DisplayName("seconds start at one, and a count at its own minimum")
    void numbersStartAtTheirMinimum() {
        Setting seconds = Setting.parse("S\tseconds\t300\tproject\tSeconds.");
        Setting count = Setting.parse("C\tcount:2\t5\tproject\tCount.");
        assertEquals(Optional.empty(), seconds.problemWith("1"));
        assertEquals(Optional.of("S takes 1 or more, not '0'."), seconds.problemWith("0"));
        assertEquals(Optional.of("S takes a whole number, not 'soon'."), seconds.problemWith("soon"));
        assertEquals(Optional.empty(), count.problemWith("2"));
        assertEquals(Optional.of("C takes 2 or more, not '1'."), count.problemWith("1"));
    }

    @Test
    @DisplayName("a share is from 0 to 100, both included")
    void aShareIsFromNoneToAll() {
        Setting share = Setting.parse("P\tshare\t80\tproject\tShare.");
        assertEquals(Optional.empty(), share.problemWith("0"));
        assertEquals(Optional.empty(), share.problemWith("100"));
        assertEquals(Optional.of("P takes a share from 0 to 100, not '-1'."), share.problemWith("-1"));
        assertEquals(Optional.of("P takes a share from 0 to 100, not '101'."), share.problemWith("101"));
        assertEquals(Optional.of("P takes a share from 0 to 100, not 'most'."), share.problemWith("most"));
    }

    @Test
    @DisplayName("a flag is 0 or 1, a choice one of its values, a word has no spaces, mutators are names in capitals")
    void theOtherKinds() {
        Setting flag = Setting.parse("F\tflag\t0\tproject\tFlag.");
        Setting choice = Setting.parse("K\tchoice:always,never\talways\tproject\tChoice.");
        Setting word = Setting.parse("W\tword\tx\tproject\tWord.");
        Setting mutators = Setting.parse("M\tmutators\tALL\tproject\tMutators.");
        assertEquals(Optional.empty(), flag.problemWith("0"));
        assertEquals(Optional.empty(), flag.problemWith("1"));
        assertEquals(Optional.of("F takes one of 0, 1, not 'yes'."), flag.problemWith("yes"));
        assertEquals(Optional.empty(), choice.problemWith("never"));
        assertEquals(Optional.of("K takes one of always, never, not 'often'."), choice.problemWith("often"));
        assertEquals(Optional.empty(), word.problemWith("kaizten/sheriff@sha256:abc"));
        assertEquals(Optional.of("W takes a value with no spaces, not 'a b'."), word.problemWith("a b"));
        assertEquals(Optional.empty(), mutators.problemWith("DEFAULTS,REMOVE_CONDITIONALS"));
        assertEquals(Optional.of("M takes PIT mutators in capitals, separated by commas, not 'all'."),
                mutators.problemWith("all"));
        assertEquals(Optional.empty(), mutators.problemWith(""), "the empty value puts the default back");
    }
}
