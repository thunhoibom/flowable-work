package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

public final class HttpStatusMatcher {

    private HttpStatusMatcher() {
    }

    public static boolean matches(int status, List<String> patterns) {
        String code = Integer.toString(status);
        for (String pattern : patterns) {
            if (pattern.endsWith("XX") && code.charAt(0) == pattern.charAt(0)) {
                return true;
            }
            if (pattern.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
