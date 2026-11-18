package io.projectaxiom.organization;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Profile("local")
public class OrganizationController {
    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Tenant createTenant(@Valid @RequestBody TenantRequest request) {
        return service.createTenant(request.code(), request.name());
    }

    @PostMapping("/tenants/{tenantId}/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public Organization createOrganization(
            @PathVariable UUID tenantId,
            @Valid @RequestBody OrganizationRequest request) {
        return service.createOrganization(tenantId, request.parentId(), request.code(),
                request.name(), request.type());
    }

    @GetMapping("/tenants/{tenantId}/organizations")
    public List<Organization> listOrganizations(@PathVariable UUID tenantId) {
        return service.listOrganizations(tenantId);
    }

    public record TenantRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 160) String name) {
    }

    public record OrganizationRequest(
            UUID parentId,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "LEGAL_ENTITY|BUSINESS_UNIT|BRANCH|DEPARTMENT|WAREHOUSE|OTHER") String type) {
    }
}
