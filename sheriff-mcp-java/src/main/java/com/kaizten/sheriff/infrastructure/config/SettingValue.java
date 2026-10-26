package com.kaizten.sheriff.infrastructure.config;

/**
 * One setting as it stands: the value in force, where it comes from, and what
 * each file sets it to, as the settings page shows it.
 *
 * @param setting the setting
 * @param value the value in force, as a person reads it: {@code worked out}
 *     for a default the code works out
 * @param source where that value comes from: {@code the environment}, a
 *     file's path, or {@code default}
 * @param inUserFile what the user's file sets it to, as written; empty when
 *     it does not
 * @param inProjectFile what the project's file sets it to, as written; empty
 *     when it does not
 */
public record SettingValue(Setting setting, String value, String source, String inUserFile, String inProjectFile) {
}
