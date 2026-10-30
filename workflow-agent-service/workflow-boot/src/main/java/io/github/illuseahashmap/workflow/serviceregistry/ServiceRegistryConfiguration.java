package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.application.PublishedServiceInvoker;
import io.github.illuseahashmap.serviceregistry.domain.HttpTransport;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcServiceCatalog;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdkHttpTransport;
import io.github.illuseahashmap.serviceregistry.infrastructure.ServiceSecretCipher;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceRegistryConfiguration {

    @Bean
    ServiceSecretCipher serviceSecretCipher(
            @Value("${workflow.service.secrets.master-key-base64:}") String masterKeyBase64) {
        return new ServiceSecretCipher(masterKeyBase64);
    }

    @Bean
    ServiceCatalog serviceCatalog(DataSource dataSource, ServiceSecretCipher serviceSecretCipher) {
        return new JdbcServiceCatalog(dataSource, serviceSecretCipher);
    }

    @Bean
    HttpTransport serviceHttpTransport() {
        return new JdkHttpTransport();
    }

    @Bean
    PublishedServiceInvoker publishedServiceInvoker(ServiceCatalog serviceCatalog, HttpTransport serviceHttpTransport) {
        return new PublishedServiceInvoker(serviceCatalog, new ServiceCaller(serviceHttpTransport));
    }
}
