package com.kaizten.sheriff.infrastructure.build;

/**
 * One error of the compiler's: where it is and what it says.
 *
 * @param file the file, relative to the mount and written with {@code /},
 *     as Sheriff names it; as the compiler wrote it when it is outside
 * @param line the line, from 1
 * @param message what the compiler said
 */
public record CompileError(String file, int line, String message) {
}
