package io.projectaxiom.bootstrap.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import io.projectaxiom.identity.IdentityService;

/**
 * OIDC resource-server boundary. Tokens are validated against the configured issuer,
 * standard JWT time constraints, and the API's required audience.
 */
@Configuration
@EnableMethodSecurity
public class OidcSecurityConfiguration {
    @Bean
    SecurityFilterChain axiomSecurityFilterChain(
            HttpSecurity http,
            Environment environment,
            AxiomJwtAuthenticationConverter authenticationConverter,
            IdentityService identityService,
            ObjectProvider<JwtDecoder> jwtDecoderProvider) throws Exception {
        OidcSettings oidc = resolveOidcSettings(environment);
        boolean localProfile = environment.acceptsProfiles(Profiles.of("local"));

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/actuator/health", "/actuator/info", "/api/v1/system")
                            .permitAll();
                    if (oidc.configured()) {
                        authorize.anyRequest().authenticated();
                    } else if (localProfile) {
                        // Explicitly development-only: existing local-profile APIs are not production auth.
                        authorize.anyRequest().permitAll();
                    } else {
                        // Fail closed until a trusted issuer AND API audience are configured.
                        authorize.anyRequest().denyAll();
                    }
                });

        if (oidc.configured()) {
            http.addFilterAfter(new TenantContextFilter(identityService), BearerTokenAuthenticationFilter.class);
            JwtDecoder decoder = jwtDecoderProvider.getIfAvailable(() -> createJwtDecoder(oidc));
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                    .decoder(decoder)
                    .jwtAuthenticationConverter(authenticationConverter)));
        }
        return http.build();
    }


    private static JwtDecoder createJwtDecoder(OidcSettings oidc) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(oidc.issuerUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(oidc.issuerUri()),
                new AudienceValidator(oidc.audience())));
        return decoder;
    }

    static OidcSettings resolveOidcSettings(Environment environment) {
        return new OidcSettings(
                setting(environment, "AXIOM_OIDC_ISSUER_URI", "axiom.security.oidc.issuer-uri"),
                setting(environment, "AXIOM_OIDC_AUDIENCE", "axiom.security.oidc.audience"));
    }

    private static String setting(Environment environment, String environmentVariable, String propertyName) {
        String value = environment.getProperty(environmentVariable);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return environment.getProperty(propertyName, "");
    }

    record OidcSettings(String issuerUri, String audience) {
        boolean configured() {
            return !issuerUri.isBlank() && !audience.isBlank();
        }
    }
}
