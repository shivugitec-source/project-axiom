package io.projectaxiom.bootstrap.system;
import io.projectaxiom.common.PlatformConstants;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class SystemController {
 @GetMapping(PlatformConstants.API_BASE_PATH + "/system")
 public SystemInformation systemInformation() { return new SystemInformation(PlatformConstants.PLATFORM_NAME, "0.1.0-SNAPSHOT", "RUNNING", Instant.now()); }
 public record SystemInformation(String name, String version, String status, Instant timestamp) {}
}