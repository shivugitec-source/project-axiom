package io.projectaxiom.bootstrap.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Requires an access token to be explicitly issued for this API.
 * A valid signature and issuer alone do not establish the intended recipient.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error(
            "invalid_token",
            "The access token does not contain the configured API audience",
            null);

    private final String requiredAudience;

    public AudienceValidator(String requiredAudience) {
        if (requiredAudience == null || requiredAudience.isBlank()) {
            throw new IllegalArgumentException("requiredAudience must be nonblank");
        }
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return token.getAudience() != null && token.getAudience().contains(requiredAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
    }
}
