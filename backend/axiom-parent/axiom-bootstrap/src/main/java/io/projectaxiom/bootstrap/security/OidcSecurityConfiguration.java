package io.projectaxiom.bootstrap.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OIDC resource-server boundary. Tokens are validated against the configured issuer,
 * standard JWT time constraints, and the API's required audience.
 */
@Configuration
public class OidcSecurityConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "axiom.security.oidc", name = {"issuer-uri", "audience"})
    JwtDecoder axiomJwtDecoder(
            @Value("${axiom.security.oidc.issuer-uri}") String issuerUri,
            @Value("${axiom.security.oidc.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(audience)));
        return decoder;
    }

    @Bean
    SecurityFilterChain axiomSecurityFilterChain(
            HttpSecurity http,
            Environment environment,
            org.springframework.beans.factory.ObjectProvider<JwtDecoder> decoderProvider,
            @Value("${axiom.security.oidc.issuer-uri:}") String issuerUri,
            @Value("${axiom.security.oidc.audience:}") String audience) throws Exception {
        boolean oidcConfigured = !issuerUri.isBlank() && !audience.isBlank();
        boolean localProfile = environment.acceptsProfiles(Profiles.of("local"));

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/actuator/health", "/actuator/info", "/api/v1/system")
                            .permitAll();
                    if (oidcConfigured) {
                        authorize.anyRequest().authenticated();
                    } else if (localProfile) {
                        // Explicitly development-only: existing local-profile APIs are not production auth.
                        authorize.anyRequest().permitAll();
                    } else {
                        // Fail closed until a trusted issuer AND API audience are configured.
                        authorize.anyRequest().denyAll();
                    }
                });

        if (oidcConfigured) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                    jwt.decoder(decoderProvider.getObject())));
        }
        return http.build();
    }
}
