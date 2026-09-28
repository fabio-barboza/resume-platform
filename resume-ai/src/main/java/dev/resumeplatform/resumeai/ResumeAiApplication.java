package dev.resumeplatform.resumeai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ResumeAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResumeAiApplication.class, args);
    }
}
