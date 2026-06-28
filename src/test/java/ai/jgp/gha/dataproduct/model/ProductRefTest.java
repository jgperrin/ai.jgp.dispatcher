package ai.jgp.gha.dataproduct.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ProductRef#hasId()}. */
class ProductRefTest {

    @Test
    void hasId_trueWhenIdPresent() {
        assertTrue(new ProductRef("prod-1", "1.0.0").hasId());
    }

    @Test
    void hasId_falseWhenIdNull() {
        assertFalse(new ProductRef(null, "1.0.0").hasId());
    }

    @Test
    void hasId_falseWhenIdBlank() {
        assertFalse(new ProductRef("   ", "1.0.0").hasId());
    }
}
