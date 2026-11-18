package io.projectaxiom.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class PlatformConstantsTest {
 @Test void exposesStablePlatformIdentity() { assertEquals("Project Axiom", PlatformConstants.PLATFORM_NAME); assertEquals("/api/v1", PlatformConstants.API_BASE_PATH); }
}