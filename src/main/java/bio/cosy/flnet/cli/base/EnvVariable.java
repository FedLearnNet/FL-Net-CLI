package bio.cosy.flnet.cli.base;

import java.util.Map;

/**
 * A variable of a deployment's {@code .env}. Implemented by enums whose constant names are the
 * variable names; {@link #description()} translates the name into what it means and is written as
 * comment above the variable and shown by {@code info --env}.
 */
public interface EnvVariable {

    /** The enum constant name, i.e. the variable name in {@code .env}. */
    String name();

    default String key() {
        return name();
    }

    /** What the variable means, in plain words. */
    String description();

    /** The value in {@code env}, or {@code null} if it is not set. */
    default String in(Map<String, String> env) {
        return env.get(key());
    }

    default String in(Map<String, String> env, String fallback) {
        return env.getOrDefault(key(), fallback);
    }
}
