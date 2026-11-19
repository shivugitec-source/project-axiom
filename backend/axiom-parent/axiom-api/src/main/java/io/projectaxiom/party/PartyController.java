package io.projectaxiom.party;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/parties")
@Profile("local")
public class PartyController {
    private final PartyService service;

    public PartyController(PartyService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Party createParty(@PathVariable UUID tenantId, @Valid @RequestBody PartyRequest request) {
        return service.createParty(tenantId, request.partyType(), request.displayName(),
                request.roles() == null ? Set.of() : request.roles());
    }

    @GetMapping
    public List<Party> listParties(@PathVariable UUID tenantId) {
        return service.listParties(tenantId);
    }

    @GetMapping("/{partyId}")
    public Party getParty(@PathVariable UUID tenantId, @PathVariable UUID partyId) {
        return service.getParty(tenantId, partyId);
    }

    public record PartyRequest(
            @NotNull PartyType partyType,
            @NotBlank @Size(max = 200) String displayName,
            Set<PartyRole> roles) {
    }
}
