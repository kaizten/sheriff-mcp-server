package com.kaizten.sheriff.infrastructure.catalog;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes a rule catalog read out of the Sheriff image.
 *
 * <p>An interface so that deciding <em>whether</em> to extract can be tested
 * without Docker, apart from the extraction itself.
 */
@FunctionalInterface
public interface CatalogExtraction {

    /**
     * Extracts the catalog into a file.
     *
     * @param catalog where to write it
     * @throws IOException when it cannot be written
     */
    void into(Path catalog) throws IOException;
}
