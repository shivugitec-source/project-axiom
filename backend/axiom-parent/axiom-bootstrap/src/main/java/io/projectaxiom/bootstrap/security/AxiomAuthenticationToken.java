package io.projectaxiom.bootstrap.security;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Authentication established only after the resource-server JwtDecoder has validated
 * the token. No tenant roles or permissions are inferred from identity-provider claims.
 */
public final class AxiomAuthenticationToken extends AbstractAuthenticationToken {
    private final Jwt jwt;
    private final AxiomPrincipal principal;

    public AxiomAuthenticationToken(Jwt jwt, AxiomPrincipal principal) {
        super(AuthorityUtils.NO_AUTHORITIES);
        this.jwt = jwt;
        this.principal = principal;
        super.setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public AxiomPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.identityId().toString();
    }
}
