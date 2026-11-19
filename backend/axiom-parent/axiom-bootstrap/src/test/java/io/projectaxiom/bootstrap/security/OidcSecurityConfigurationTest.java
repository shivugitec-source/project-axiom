package io.projectaxiom.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OidcSecurityConfigurationTest {
    @Test
    void resolvesDocumentedEnvironmentVariables() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AXIOM_OIDC_ISSUER_URI", "https://identity.example.test/issuer")
                .withProperty("AXIOM_OIDC_AUDIENCE", "axiom-api");

        OidcSecurityConfiguration.OidcSettings settings =
                OidcSecurityConfiguration.resolveOidcSettings(environment);

        assertThat(settings.configured()).isTrue();
        assertThat(settings.issuerUri()).isEqualTo("https://identity.example.test/issuer");
        assertThat(settings.audience()).isEqualTo("axiom-api");
    }

    @Test
    void supportsExplicitSpringPropertyNames() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("axiom.security.oidc.issuer-uri", "https://identity.example.test/issuer")
                .withProperty("axiom.security.oidc.audience", "axiom-api");

        OidcSecurityConfiguration.OidcSettings settings =
                OidcSecurityConfiguration.resolveOidcSettings(environment);

        assertThat(settings.configured()).isTrue();
    }

    @Test
    void partialConfigurationDoesNotEnableOidc() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AXIOM_OIDC_ISSUER_URI", "https://identity.example.test/issuer");

        OidcSecurityConfiguration.OidcSettings settings =
                OidcSecurityConfiguration.resolveOidcSettings(environment);

        assertThat(settings.configured()).isFalse();
    }

    @Test
    void blankEnvironmentVariableFallsBackToSpringProperty() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AXIOM_OIDC_ISSUER_URI", " ")
                .withProperty("axiom.security.oidc.issuer-uri", "https://identity.example.test/issuer")
                .withProperty("axiom.security.oidc.audience", "axiom-api");

        OidcSecurityConfiguration.OidcSettings settings =
                OidcSecurityConfiguration.resolveOidcSettings(environment);

        assertThat(settings.configured()).isTrue();
        assertThat(settings.issuerUri()).isEqualTo("https://identity.example.test/issuer");
    }
}
