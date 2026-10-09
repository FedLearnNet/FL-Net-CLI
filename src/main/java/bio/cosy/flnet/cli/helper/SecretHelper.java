package bio.cosy.flnet.cli.helper;

import java.security.SecureRandom;

public final class SecretHelper {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private SecretHelper() {
    }

    /**
     * Generates a random secret string of the specified length.
     * Uses only alphanumeric characters (A-Z, a-z, 0-9) for the secret.
     *
     * @param length the length of the secret string to generate
     * @return the generated secret string
     */
    public static String generate(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder secret = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            secret.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return secret.toString();
    }
}
