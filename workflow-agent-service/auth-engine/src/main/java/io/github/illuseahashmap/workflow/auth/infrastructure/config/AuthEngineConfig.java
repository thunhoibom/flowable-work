package io.github.illuseahashmap.workflow.auth.infrastructure.config;

import io.github.illuseahashmap.workflow.auth.infrastructure.eim.EimProperties;
import io.github.illuseahashmap.workflow.auth.infrastructure.keycloak.KeycloakProperties;
import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AuthTokenProperties.class,
        AuthenticationProtectionProperties.class,
        KeycloakProperties.class,
        EimProperties.class
})
public class AuthEngineConfig {
}
