package io.projectaxiom.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import io.projectaxiom.identity.IdentityService;
import io.projectaxiom.identity.IdentityUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;

class AxiomJwtAuthenticationConverterTest {
    private final IdentityService identityService = mock(IdentityService.class);
    private final AxiomJwtAuthenticationConverter converter =
            new AxiomJwtAuthenticationConverter(identityService);

    @Test
    void mapsIssuerAndSubjectToAnAuthenticatedAxiomPrincipalWithoutAuthorities() {
        String issuer = "https://identity.example.test/realms/axiom";
        String subject = "external-subject-123";
        UUID identityId = UUID.randomUUID();
        when(identityService.registerIdentity(issuer, subject)).thenReturn(
                new IdentityUser(identityId, issuer, subject, null, null, "ACTIVE", Instant.now()));

        AxiomAuthenticationToken authentication = converter.convert(jwt(issuer, subject));

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo(identityId.toString());
        assertThat(authentication.getPrincipal())
                .isEqualTo(new AxiomPrincipal(identityId, issuer, subject));
        assertThat(authentication.getAuthorities()).isEmpty();
        verify(identityService).registerIdentity(issuer, subject);
    }

    @Test
    void rejectsTokenWithoutSubjectBeforeResolvingAnIdentity() {
        Jwt token = jwt("https://identity.example.test/realms/axiom", null);

        assertThatThrownBy(() -> converter.convert(token))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(identityService);
    }

    @Test
    void rejectsDisabledAxiomIdentity() {
        String issuer = "https://identity.example.test/realms/axiom";
        String subject = "disabled-subject";
        when(identityService.registerIdentity(issuer, subject)).thenReturn(
                new IdentityUser(UUID.randomUUID(), issuer, subject, null, null, "DISABLED", Instant.now()));

        assertThatThrownBy(() -> converter.convert(jwt(issuer, subject)))
                .isInstanceOf(DisabledException.class);
        verify(identityService).registerIdentity(issuer, subject);
    }

    private Jwt jwt(String issuer, String subject) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Jwt.Builder builder = Jwt.withTokenValue("validated-test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }
}
