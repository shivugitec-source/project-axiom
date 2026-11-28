# Sugar Industry — First Business Capability

## Goal
Build the first sugar-mill operational workflow incrementally, beginning with supplier-linked cane weighment records. The domain foundation is not a production weighbridge integration or an approved procurement workflow.

## Implemented invariants

### Exact net cane weight
- All input and output quantities are explicitly in kilograms.
- Gross weight and tare weight are required, nonnegative decimal quantities.
- Tare weight cannot exceed gross weight.
- Net weight equals gross weight minus tare weight, calculated exactly with `BigDecimal`.
- Zero net weight is mathematically valid at this layer; acceptance or rejection belongs to a future business workflow.

### Cane delivery weighment record
- An immutable record associates a tenant, mill organization, supplier party, weighbridge, vehicle registration, weighment and capture timestamp.
- Required identifiers, weighment and capture time cannot be null; vehicle registration cannot be blank.
- The record preserves exact decimal weighment values and the supplied capture timestamp.
- The record does not itself prove that a referenced supplier, mill or weighbridge exists or belongs to the tenant; persistence must enforce those relationships with tenant-consistent foreign keys and authorization.

## Proposed follow-on requirements (not yet implemented)
1. Define tenant-owned mill and weighbridge master records and authorized scale operators.
2. Define delivery/ticket identity, duplicate prevention, lifecycle and audit requirements with mill operators.
3. Persist supplier-linked delivery weighments using tenant-consistent foreign keys and forced RLS.
4. Expose authenticated APIs with explicit tenant permission checks; remove local-profile-only restrictions only after production authorization is ready.
5. Define scale integration, calibration evidence, unit conversion, acceptance/rejection, quality, recovery and procurement settlement with validated business rules.

## Explicit exclusions from the current increment
No HTTP endpoints, database tables, migration, scale integration, delivery acceptance, quality/recovery policy, financial settlement, price policy, sugar production, inventory posting or production deployment. Domain and regulatory rules must be validated before introducing them.
