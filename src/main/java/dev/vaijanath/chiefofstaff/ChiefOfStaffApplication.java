package dev.vaijanath.chiefofstaff;

import dev.vaijanath.chiefofstaff.config.CosProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Chief of Staff — Java port of the Python multi-agent assistant, on the java-ai-agent framework.
 *
 * <p>Exposes each agent as an OpenAI-compatible "model" (agent-&lt;x&gt;) so Open WebUI can chat with it.
 * This is the scaffold: only the tool-less Comms agent is wired. The router supervisor and the tool
 * agents (researcher, notes, code, meeting, handoff) land in later port steps.
 */
@SpringBootApplication
@EnableConfigurationProperties(CosProperties.class)
public class ChiefOfStaffApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChiefOfStaffApplication.class, args);
    }
}
