# Sugar Industry — First Business Capability

## Goal
Introduce a small, independently testable foundation for sugarcane weighment. This is an initial domain calculation, not a production weighbridge integration or an approved procurement workflow.

## Implemented invariant: net cane weight
- All input and output quantities are explicitly in kilograms.
- Gross weight and tare weight are required, nonnegative decimal quantities.
- Tare weight cannot exceed gross weight.
- Net weight equals gross weight minus tare weight, calculated exactly with `BigDecimal`.
- Zero net weight is mathematically valid at this layer; acceptance or rejection of a delivery belongs to a future business workflow.
- The calculation is immutable, stateless, and independent of tenant identity, database access and external systems.

## Proposed follow-on requirements (not yet implemented)
1. Tenant-owned sugar mill and weighbridge identities, with authorized scale operators.
2. Cane delivery lifecycle, supplier/farmer linkage, vehicle and trip identifiers, duplicate prevention and audit trail.
3. Scale integration, calibrated weight provenance, unit conversion policy and adjustment authorization.
4. Delivery acceptance and rejection rules, quality sampling and procurement settlement.
5. Tenant-isolated persistence with forced RLS, authorization checks and additive migrations.

## Explicit exclusions from this increment
No HTTP endpoints, database tables, migration, financial settlement, recovery calculations, price policy, sugar production, inventory posting, external integrations or production deployment. Domain and regulatory rules must be validated before introducing them.
