package io.projectaxiom.identity;

/** A platform-owned permission that tenant roles may be granted. */
public record IamPermission(String code, String description) {
}
