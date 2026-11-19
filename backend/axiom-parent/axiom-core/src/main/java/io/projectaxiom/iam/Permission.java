package io.projectaxiom.iam;

/** A platform-defined capability that may be granted to a tenant role. */
public record Permission(String code, String description) {
}
