package com.kaizten.sheriff.infrastructure.docker;

/**
 * A {@code fix} that did not run: Docker could not start it, or Sheriff
 * refused it with a reason on stderr and nothing on stdout.
 *
 * <p>Thrown inside {@link SheriffRepair} and turned there into the outcome's
 * failure, so that a repair that never happened is not reported as one that
 * found nothing to do.
 */
final class FixNotRun extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Records why the {@code fix} did not run.
     *
     * @param reason the reason, as Docker or Sheriff gave it
     */
    FixNotRun(String reason) {
        super(reason);
    }
}
