package io.projectaxiom.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import io.projectaxiom.identity.IdentityService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletException;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantContextFilterTest {
    private final IdentityService identityService = mock(IdentityService.class);
    private final TenantContextFilter filter = new TenantContextFilter(identityService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void establishesContextOnlyAfterActiveMembershipVerification() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new AxiomAuthenticationToken(null, new AxiomPrincipal(identityId, "issuer", "subject")));
        when(identityService.hasActiveMembership(tenantId, identityId)).thenReturn(true);
        MockHttpServletRequest request = request(tenantId.toString().toUpperCase(Locale.ROOT));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> assertThat(TenantContext.currentTenantId()).isEqualTo(tenantId);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(TenantContext.currentTenantId()).isNull();
        verify(identityService).hasActiveMembership(tenantId, identityId);
    }

    @Test
    void deniesTenantWithoutActiveMembership() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new AxiomAuthenticationToken(null, new AxiomPrincipal(identityId, "issuer", "subject")));
        when(identityService.hasActiveMembership(tenantId, identityId)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(tenantId.toString()), response, (req, res) -> {
            throw new AssertionError("request must not continue");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsMalformedTenantHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("not-a-uuid"), response, (req, res) -> {
            throw new AssertionError("request must not continue");
        });
        assertThat(response.getStatus()).isEqualTo(400);
        verify(identityService, never()).hasActiveMembership(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsNonCanonicalTenantUuidSpellingsBeforeMembershipLookup() throws Exception {
        for (String nonCanonical : List.of("1-1-1-1-1", "+1-1-1-1-1")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(nonCanonical), response, (req, res) -> {
                throw new AssertionError("non-canonical tenant selector must not continue");
            });
            assertThat(response.getStatus()).isEqualTo(400);
        }

        verify(identityService, never()).hasActiveMembership(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }


    @Test
    void rejectsDuplicateTenantHeadersAsAmbiguous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantContextFilter.TENANT_HEADER, UUID.randomUUID().toString());
        request.addHeader(TenantContextFilter.TENANT_HEADER, UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("ambiguous tenant selector must not continue");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        verify(identityService, never()).hasActiveMembership(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsBlankTenantHeaderInsteadOfTreatingItAsAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("  "), response, (req, res) -> {
            throw new AssertionError("blank tenant selector must not continue");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        verify(identityService, never()).hasActiveMembership(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsTenantSelectorWithoutAuthenticatedAxiomPrincipal() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(tenantId.toString()), response, (req, res) -> {
            throw new AssertionError("request must not continue");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        verify(identityService, never()).hasActiveMembership(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void clearsTenantContextWhenDownstreamProcessingFails() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new AxiomAuthenticationToken(null, new AxiomPrincipal(identityId, "issuer", "subject")));
        when(identityService.hasActiveMembership(tenantId, identityId)).thenReturn(true);

        assertThatThrownBy(() -> filter.doFilter(request(tenantId.toString()), new MockHttpServletResponse(),
                (req, res) -> {
                    assertThat(TenantContext.currentTenantId()).isEqualTo(tenantId);
                    throw new ServletException("downstream failure");
                })).isInstanceOf(ServletException.class);

        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void allowsRequestsWithoutTenantSelectorToContinueWithoutContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertThat(TenantContext.currentTenantId()).isNull());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantContextFilter.TENANT_HEADER, tenantId);
        return request;
    }
}
