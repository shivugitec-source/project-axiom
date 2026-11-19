package io.projectaxiom.bootstrap.security;

import java.util.UUID;

/**
 * A validated external subject mapped to a stable Project Axiom identity.
 * Tenant membership and permissions are deliberately not represented here.
 */
public record AxiomPrincipal(UUID identityId, String issuer, String subject) {
}
