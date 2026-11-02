package io.github.illuseahashmap.serviceregistry.domain;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4. The signature covers the request that is about to be sent,
 * including the host and the payload hash.
 */
public final class AwsSigV4 {

    private static final DateTimeFormatter AMZ_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String EMPTY_PAYLOAD =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private AwsSigV4() {
    }

    public static void sign(
            HttpMethod method,
            String url,
            Map<String, String> headers,
            byte[] payload,
            String accessKey,
            String secret,
            String region,
            String service,
            Instant now) {
        if (accessKey == null || accessKey.isBlank() || secret == null || secret.isBlank()) {
            throw new InvalidServiceRequestException("AWS credentials are required");
        }
        if (region == null || region.isBlank() || service == null || service.isBlank()) {
            throw new InvalidServiceRequestException("AWS region and service are required");
        }
        URI uri = URI.create(url);
        String amzDate = AMZ_TIME.format(now);
        String date = amzDate.substring(0, 8);
        byte[] body = payload == null ? new byte[0] : payload;
        String payloadHash = body.length == 0 ? EMPTY_PAYLOAD : hex(sha256(body));
        headers.put("host", host(uri));
        headers.put("x-amz-date", amzDate);
        // The payload hash is the last line of the canonical request. It is not a separate header
        // in the published IAM example, so the signature can be checked against that example.

        String canonicalHeaders = canonicalHeaders(headers);
        String signedHeaders = signedHeaders(headers);
        String canonical = method.name() + "\n"
                + canonicalPath(uri)
                + "\n"
                + canonicalQuery(uri)
                + "\n"
                + canonicalHeaders
                + "\n"
                + signedHeaders
                + "\n"
                + payloadHash;
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date), region), service), "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));
        headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
    }

    private static String host(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        if (port > 0 && port != defaultPort(uri.getScheme())) {
            return host + ":" + port;
        }
        return host;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static String canonicalPath(URI uri) {
        String raw = uri.getRawPath();
        if (raw == null || raw.isEmpty()) {
            return "/";
        }
        String[] segments = raw.split("/", -1);
        StringBuilder path = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                path.append('/');
            }
            path.append(awsEncode(urlDecode(segments[index])));
        }
        return path.toString();
    }

    private static String canonicalQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (String piece : raw.split("&", -1)) {
            int split = piece.indexOf('=');
            String name = split < 0 ? piece : piece.substring(0, split);
            String value = split < 0 ? "" : piece.substring(split + 1);
            pairs.add(awsEncode(urlDecode(name)) + "=" + awsEncode(urlDecode(value)));
        }
        pairs.sort(String::compareTo);
        return String.join("&", pairs);
    }

    private static String canonicalHeaders(Map<String, String> headers) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> header : sorted(headers).entrySet()) {
            builder.append(header.getKey()).append(':').append(normalize(header.getValue())).append('\n');
        }
        return builder.toString();
    }

    private static String signedHeaders(Map<String, String> headers) {
        return String.join(";", sorted(headers).keySet());
    }

    private static TreeMap<String, String> sorted(Map<String, String> headers) {
        TreeMap<String, String> sorted = new TreeMap<>();
        headers.forEach((name, value) -> {
            if (name != null && !"authorization".equalsIgnoreCase(name)) {
                sorted.put(name.toLowerCase(Locale.ROOT), value == null ? "" : value);
            }
        });
        return sorted;
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String awsEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte unit : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = unit & 0xff;
            if (unreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(String.format("%02X", unsigned));
            }
        }
        return encoded.toString();
    }

    private static boolean unreserved(int unsigned) {
        return (unsigned >= 'A' && unsigned <= 'Z')
                || (unsigned >= 'a' && unsigned <= 'z')
                || (unsigned >= '0' && unsigned <= '9')
                || unsigned == '-'
                || unsigned == '_'
                || unsigned == '.'
                || unsigned == '~';
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is required", exception);
        }
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
