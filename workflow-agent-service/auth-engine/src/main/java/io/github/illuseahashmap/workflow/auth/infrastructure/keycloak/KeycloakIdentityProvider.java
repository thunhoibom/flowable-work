package io.github.illuseahashmap.workflow.auth.infrastructure.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalIdentityProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Keycloak OpenID Connect token endpoint client (password and refresh_token grants, logout). */
@Component
public class KeycloakIdentityProvider implements ExternalIdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvider.class);
    private static final ObjectMapper ERROR_READER = new ObjectMapper();
    private static final ParameterizedTypeReference<Map<String, Object>> TOKEN_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final KeycloakProperties properties;
    private final RestClient restClient;

    public KeycloakIdentityProvider(KeycloakProperties properties) {
        this.properties = properties;
        // java.net.http reads the body of 401 responses (Keycloak's invalid_grant), which
        // HttpURLConnection does not reliably expose.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public ExternalTokens passwordGrant(String username, String password) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid");
        return requestTokens(form, "Invalid username or password");
    }

    @Override
    public ExternalTokens refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Session expired");
        }
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return requestTokens(form, "Session expired");
    }

    @Override
    public void logout(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            return;
        }
        MultiValueMap<String, String> form = clientForm();
        form.add("refresh_token", refreshToken);
        try {
            restClient.post()
                    .uri(properties.logoutEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            // Local cookies are cleared regardless; an already-expired SSO session is not an error.
            log.info("Keycloak logout was not completed: {}", exception.getMessage());
        }
    }

    private ExternalTokens requestTokens(MultiValueMap<String, String> form, String rejectedMessage) {
        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri(properties.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(TOKEN_RESPONSE);
        } catch (RestClientResponseException exception) {
            throw mapError(exception, rejectedMessage);
        } catch (RestClientException exception) {
            log.error("Keycloak token endpoint is unreachable: {}", exception.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Identity provider is unavailable");
        }
        if (body == null || !(body.get("access_token") instanceof String accessToken)
                || !StringUtils.hasText(accessToken)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Identity provider returned no access token");
        }
        Object refreshToken = body.get("refresh_token");
        return new ExternalTokens(
                accessToken,
                asLong(body.get("expires_in")),
                refreshToken instanceof String value ? value : null,
                asLong(body.get("refresh_expires_in")));
    }

    private BusinessException mapError(RestClientResponseException exception, String rejectedMessage) {
        String error = null;
        String description = null;
        try {
            JsonNode node = ERROR_READER.readTree(exception.getResponseBodyAsString());
            error = node.path("error").asText(null);
            description = node.path("error_description").asText(null);
        } catch (Exception ignored) {
            // Non-JSON error body; fall through to status based mapping.
        }
        if ("invalid_grant".equals(error)) {
            if ("Account disabled".equals(description)) {
                return new BusinessException(ErrorCode.FORBIDDEN, "Account is disabled");
            }
            return new BusinessException(ErrorCode.UNAUTHORIZED, rejectedMessage);
        }
        log.error("Keycloak rejected the request: status={}, error={}, description={}",
                exception.getStatusCode().value(), error, description);
        if (exception.getStatusCode().is4xxClientError()) {
            return new BusinessException(ErrorCode.INTERNAL_ERROR, "Identity provider rejected the client configuration");
        }
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "Identity provider is unavailable");
    }

    private MultiValueMap<String, String> clientForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getClientId());
        if (StringUtils.hasText(properties.getClientSecret())) {
            form.add("client_secret", properties.getClientSecret());
        }
        return form;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
