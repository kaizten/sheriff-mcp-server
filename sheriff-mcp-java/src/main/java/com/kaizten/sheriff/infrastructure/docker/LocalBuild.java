package com.kaizten.sheriff.infrastructure.docker;

/**
 * One build of Sheriff's image as it is on this machine.
 *
 * @param digest the digest it was pulled by, or the empty string when it was
 *     built here
 * @param created the day it was built, {@code YYYY-MM-DD}, or the empty
 *     string when Docker did not say
 */
public record LocalBuild(String digest, String created) {

    /**
     * What a field holds when Docker did not say.
     */
    public static final String UNKNOWN = "";
}
