package ai.jgp.gha.dataproduct;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Marks code that should be excluded from JaCoCo coverage analysis.
 * JaCoCo 0.8.2+ automatically filters methods and classes annotated
 * with any annotation whose simple name is "Generated".
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@interface Generated {
}
