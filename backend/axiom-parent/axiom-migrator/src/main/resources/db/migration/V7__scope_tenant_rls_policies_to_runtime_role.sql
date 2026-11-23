-- Restrict tenant row-level security policies to the runtime application role.
-- V2-V5 created these policies without a TO clause, which defaults to PUBLIC.
-- Preserve the exact tenant predicate while removing that broader policy audience.
DO $$
DECLARE
    tenant_table TEXT;
BEGIN
    FOREACH tenant_table IN ARRAY ARRAY[
        'organization',
        'party',
        'party_role',
        'tenant_membership',
        'tenant_role',
        'role_permission',
        'membership_role',
        'party_contact_point'
    ]
    LOOP
        EXECUTE format(
            'DROP POLICY %I ON axiom.%I',
            tenant_table || '_tenant_isolation',
            tenant_table
        );
        EXECUTE format(
            $policy$
                CREATE POLICY %I ON axiom.%I
                AS PERMISSIVE
                FOR ALL
                TO axiom_app
                USING (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID)
                WITH CHECK (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID)
            $policy$,
            tenant_table || '_tenant_isolation',
            tenant_table
        );
    END LOOP;
END $$;
