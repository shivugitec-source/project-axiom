package io.projectaxiom.bootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages = "io.projectaxiom")
public class AxiomApplication { public static void main(String[] args) { SpringApplication.run(AxiomApplication.class, args); } }