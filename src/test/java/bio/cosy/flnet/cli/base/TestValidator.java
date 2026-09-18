package bio.cosy.flnet.cli.base;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

/** Bean Validation outside Quarkus; the CLI itself uses the injected {@link Validator}. */
final class TestValidator {

    static final Validator VALIDATOR = Validation.byDefaultProvider().configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory().getValidator();

    private TestValidator() {
    }
}
