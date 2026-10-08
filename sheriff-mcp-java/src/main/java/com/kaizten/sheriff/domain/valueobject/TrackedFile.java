package com.kaizten.sheriff.domain.valueobject;

/**
 * One file Sheriff analyzed, as its {@code sheriff_tracked_files.json}
 * records it: the path and the SHA-256 of the content it saw.
 *
 * <p>The hash is what makes the record worth keeping. Two analyses of the same
 * component name, file by file, what changed between them, which is exactly
 * what a repair wants to report and what git can only answer inside a
 * repository.
 *
 * @param path the file, relative to the mounted directory
 * @param hash the SHA-256 of its content when Sheriff read it
 */
public record TrackedFile(String path, String hash) {

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * <p>A value object holds a checked value, so an absent one is refused
     * where it appears rather than replaced by something harmless-looking.
     *
     * @throws IllegalArgumentException when a required value is not defined
     */
    public TrackedFile {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(ERROR_PATH_NOT_DEFINED);
        }
        if (hash == null) {
            throw new IllegalArgumentException(ERROR_HASH_NOT_DEFINED);
        }
    }

    private static final String ERROR_PATH_NOT_DEFINED = "Path is not defined";
    private static final String ERROR_HASH_NOT_DEFINED = "Hash is not defined";
}
