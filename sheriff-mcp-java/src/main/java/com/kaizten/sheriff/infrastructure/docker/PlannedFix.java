package com.kaizten.sheriff.infrastructure.docker;

import java.util.List;

/**
 * One {@code fix} of a {@link FixPlan}, and what it is kept to.
 *
 * @param codes the rules given to {@code --fixers}, none for Sheriff's
 *     default set
 * @param files the files given to {@code --file}, as the findings name
 *     them, none for the whole component
 */
record PlannedFix(List<String> codes, List<String> files) {
}
