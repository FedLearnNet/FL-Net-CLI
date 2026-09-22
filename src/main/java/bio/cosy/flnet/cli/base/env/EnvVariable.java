package bio.cosy.flnet.cli.base.env;

import java.util.Map;

public interface EnvVariable {

    String name();

    default String key() {
        return name();
    }

    String description();

    default String in(Map<String, String> env) {
        return env.get(key());
    }

    default String in(Map<String, String> env, String fallback) {
        return env.getOrDefault(key(), fallback);
    }
}
