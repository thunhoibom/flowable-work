package io.github.illuseahashmap.serviceregistry.domain;

/**
 * Joins an environment base URL with an operation path.
 * An absolute http(s) path is used as-is so one operation can call a different host.
 */
public final class RequestUrl {

    private RequestUrl() {
    }

    public static String join(String baseUrl, String path) {
        if (path != null && (path.startsWith("https://") || path.startsWith("http://"))) {
            return path;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new InvalidServiceRequestException("Environment base URL is required");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path == null ? "" : path;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        // Exactly one slash between the host and the path.
        return base + suffix;
    }
}
