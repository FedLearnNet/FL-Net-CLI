package bio.cosy.flnet.cli.helper;

import java.security.SecureRandom;

public final class SecretHelper {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretHelper() {
    }

    public static String generate(int length) {
        StringBuilder secret = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            secret.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return secret.toString();
    }
}
