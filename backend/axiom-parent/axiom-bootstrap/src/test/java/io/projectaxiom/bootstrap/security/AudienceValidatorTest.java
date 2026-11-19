package io.projectaxiom.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void acceptsTokenContainingConfiguredAudience() {
        Jwt token = token(List.of("other-api", "axiom-api"));

        assertThat(new AudienceValidator("axiom-api").validate(token).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenForDifferentAudience() {
        Jwt token = token(List.of("other-api"));

        assertThat(new AudienceValidator("axiom-api").validate(token).hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithoutAudience() {
        Jwt token = token(List.of());

        assertThat(new AudienceValidator("axiom-api").validate(token).hasErrors()).isTrue();
    }

    @Test
    void requiresConfiguredAudience() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AudienceValidator(" "));
    }

    private Jwt token(List<String> audience) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer("https://identity.example.test")
                .subject("user-123")
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(300))
                .audience(audience)
                .build();
    }
}
