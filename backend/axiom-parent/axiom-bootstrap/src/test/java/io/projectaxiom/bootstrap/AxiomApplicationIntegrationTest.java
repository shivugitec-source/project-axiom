package io.projectaxiom.bootstrap;

import io.projectaxiom.bootstrap.system.SystemController.SystemInformation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AxiomApplicationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startsWithPostgresAndAppliesFlywayMigration() {
        Integer schemaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'axiom'",
                Integer.class);
        assertThat(schemaCount).isEqualTo(1);

        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axiom.flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);
        assertThat(migrationCount).isEqualTo(1);
    }

    @Test
    void exposesSystemAndHealthEndpoints() {
        var systemResponse = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/system",
                SystemInformation.class);

        assertThat(systemResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(systemResponse.getBody()).isNotNull();
        assertThat(systemResponse.getBody().name()).isEqualTo("Project Axiom");
        assertThat(systemResponse.getBody().status()).isEqualTo("RUNNING");
        assertThat(systemResponse.getBody().timestamp()).isNotNull();

        var healthResponse = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health",
                String.class);

        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthResponse.getBody()).contains("\"status\":\"UP\"");
    }
}
