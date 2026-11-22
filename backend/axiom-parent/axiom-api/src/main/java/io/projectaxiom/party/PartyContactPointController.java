package io.projectaxiom.party;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/parties/{partyId}/contact-points")
@Profile("local")
public class PartyContactPointController {
    private final PartyContactPointService service;

    public PartyContactPointController(PartyContactPointService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("@tenantPermissionAuthorization.hasPermission(authentication, #p0, 'party:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyContactPoint add(@PathVariable UUID tenantId, @PathVariable UUID partyId,
                                 @Valid @RequestBody ContactPointRequest request) {
        return service.add(tenantId, partyId, request.type(), request.value(), request.label());
    }

    @GetMapping
    @PreAuthorize("@tenantPermissionAuthorization.hasPermission(authentication, #p0, 'party:read')")
    public List<PartyContactPoint> list(@PathVariable UUID tenantId, @PathVariable UUID partyId) {
        return service.list(tenantId, partyId);
    }

    public record ContactPointRequest(
            @NotNull ContactPointType type,
            @NotBlank @Size(max = 320) String value,
            @Size(max = 80) String label) {
    }
}
