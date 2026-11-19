package io.projectaxiom.bootstrap.security;

import io.projectaxiom.identity.IdentityService;
import io.projectaxiom.identity.IdentityUser;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Maps a JWT only after the configured JwtDecoder has validated its signature,
 * issuer, time claims and audience. External role/group claims are not trusted as
 * tenant roles; membership and authorization are separate capabilities.
 */
@Component
public final class AxiomJwtAuthenticationConverter implements Converter<Jwt, Authentication> {
    private final IdentityService identityService;

    public AxiomJwtAuthenticationConverter(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public Authentication convert(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new BadCredentialsException("Validated access token must contain nonblank issuer and subject claims");
        }

        final IdentityUser identity;
        try {
            // Idempotently creates/resolves the global identity; it grants no tenant membership.
            identity = identityService.registerIdentity(issuer, subject);
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("Access token identity claims are not supported", exception);
        }

        if (!"ACTIVE".equals(identity.status())) {
            throw new DisabledException("The Project Axiom identity is disabled");
        }

        AxiomPrincipal principal = new AxiomPrincipal(identity.id(), identity.issuer(), identity.subject());
        return new AxiomAuthenticationToken(jwt, principal);
    }
}
