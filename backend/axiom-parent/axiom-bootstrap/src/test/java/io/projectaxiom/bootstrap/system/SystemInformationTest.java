package io.projectaxiom.bootstrap.system;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
class SystemInformationTest {
 @Test void systemInformationContainsPlatformIdentityAndTimestamp() {
  var info = new SystemController.SystemInformation("Project Axiom", "0.1.0-SNAPSHOT", "RUNNING", java.time.Instant.parse("2026-11-18T00:00:00Z"));
  assertEquals("Project Axiom", info.name()); assertEquals("RUNNING", info.status()); assertNotNull(info.timestamp());
 }
}