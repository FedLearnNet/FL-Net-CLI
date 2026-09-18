package bio.cosy.flnet.cli.support;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A TCP port (1-65535), for the model and the configuration. */
@Min(1)
@Max(65535)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
public @interface Port {

    String message() default "Ports must be between 1 and 65535.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
