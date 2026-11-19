package io.projectaxiom.identity;

/** Stable permission identifiers used by tenant authorization policies. */
public enum IamPermission {
    TENANT_READ("tenant:read"),
    TENANT_MEMBERS_READ("tenant:members:read"),
    TENANT_MEMBERS_MANAGE("tenant:members:manage"),
    ORGANIZATION_READ("organization:read"),
    ORGANIZATION_WRITE("organization:write"),
    PARTY_READ("party:read"),
    PARTY_WRITE("party:write");

    private final String code;

    IamPermission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
