package dev.vaijanath.chiefofstaff;

import dev.vaijanath.chiefofstaff.config.CosProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Chief of Staff — Java port of the Python multi-agent assistant, on the java-ai-agent framework.
 *
 * <p>Exposes each agent as an OpenAI-compatible "model" (agent-&lt;x&gt;) so Open WebUI can chat with it:
 * a supervisor router plus comms, code, researcher, notes, handoff, and meeting specialists.
 */
@SpringBootApplication
@EnableConfigurationProperties(CosProperties.class)
@EnableScheduling
public class ChiefOfStaffApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChiefOfStaffApplication.class, args);
    }
}
