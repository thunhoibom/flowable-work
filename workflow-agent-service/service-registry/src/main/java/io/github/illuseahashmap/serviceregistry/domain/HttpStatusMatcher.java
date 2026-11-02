package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * Matches an HTTP status against catalog error and success patterns.
 * A pattern is an exact code ({@code 409}), a class ({@code 5xx}), or an inclusive range ({@code 400-499}).
 */
public final class HttpStatusMatcher {

    private HttpStatusMatcher() {
    }

    public static boolean matches(int status, List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        String code = Integer.toString(status);
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // Editors and imports may add spaces or uppercase class letters.
            String pattern = raw.trim().toLowerCase();
            if (matchesOne(status, code, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesOne(int status, String code, String pattern) {
        int dash = pattern.indexOf('-');
        if (dash > 0) {
            return inRange(status, pattern, dash);
        }
        // "5xx" means every status in that hundreds class.
        if (pattern.endsWith("xx") && pattern.length() == 3 && code.charAt(0) == pattern.charAt(0)) {
            return true;
        }
        return pattern.equals(code);
    }

    private static boolean inRange(int status, String pattern, int dash) {
        try {
            int start = Integer.parseInt(pattern.substring(0, dash).trim());
            int end = Integer.parseInt(pattern.substring(dash + 1).trim());
            return status >= start && status <= end;
        } catch (NumberFormatException ignored) {
            // A broken pattern never matches; the caller still sees the HTTP error.
            return false;
        }
    }
}
