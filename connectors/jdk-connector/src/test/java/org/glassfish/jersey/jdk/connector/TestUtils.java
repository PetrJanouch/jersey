package org.glassfish.jersey.jdk.connector;

/**
 * Created by petr on 15/01/15.
 */
class TestUtils {

    static String generateBody(int size) {
        String pattern = "ABCDEFG";
        StringBuilder bodyBuilder = new StringBuilder();

        int fullIterations = size / pattern.length();
        for (int i = 0; i < fullIterations; i++) {
            bodyBuilder.append(pattern);
        }

        String remaining = pattern.substring(0, size - pattern.length() * fullIterations);
        bodyBuilder.append(remaining);
        return bodyBuilder.toString();
    }
}
