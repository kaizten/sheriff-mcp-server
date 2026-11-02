package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which values are given to Sheriff as a GitHub token: those with the form
 * the image of 2 November checks for, as one without it stops every run.
 */
class GitHubTokenTests {

    @Test
    @DisplayName("a token of each of GitHub's kinds is taken, without surrounding blanks")
    void takesGitHubTokens() {
        for (String prefix : new String[] {"ghp", "github_pat", "gho", "ghu", "ghs", "ghr"}) {
            assertEquals(Optional.of(prefix + "_abc-123_X"), GitHubToken.accepted(" " + prefix + "_abc-123_X\n"));
        }
    }

    @Test
    @DisplayName("nothing, an empty value or one of another form is no token")
    void refusesWhatIsNotOne() {
        assertEquals(Optional.empty(), GitHubToken.accepted(null));
        assertEquals(Optional.empty(), GitHubToken.accepted(""));
        assertEquals(Optional.empty(), GitHubToken.accepted("none"));
        assertEquals(Optional.empty(), GitHubToken.accepted("ghp_"));
        assertEquals(Optional.empty(), GitHubToken.accepted("ghp_has space"));
        assertEquals(Optional.empty(), GitHubToken.accepted("xghp_abc"));
    }

    @Test
    @DisplayName("the tests' property wins over the variable, so no one's token reaches them")
    void thePropertyWins() {
        assertEquals(Optional.empty(), GitHubToken.fromProcess());
    }
}
