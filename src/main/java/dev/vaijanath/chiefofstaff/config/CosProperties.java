package dev.vaijanath.chiefofstaff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central configuration, mirroring the Python config.py plus the model names and paths that were
 * hard-coded across the agents.
 */
@ConfigurationProperties("cos")
public record CosProperties(
        String ollamaBaseUrl,
        String model,
        String embeddingModel,
        String guardModel,
        String dbUrl,
        String dbUser,
        String dbPassword,
        int embeddingDimensions,
        String dataDir,
        String githubToken,
        String tavilyApiKey) {

    public CosProperties {
        ollamaBaseUrl = blankTo(ollamaBaseUrl, "http://localhost:11434");
        model = blankTo(model, "gemma4:31b-cloud");
        embeddingModel = blankTo(embeddingModel, "granite-embedding:30m");
        guardModel = strip(guardModel);
        dbUrl = blankTo(dbUrl, "jdbc:postgresql://localhost:5432/chief_of_staff");
        dbUser = blankTo(dbUser, "cos");
        dbPassword = blankTo(dbPassword, "cos_local_dev");
        embeddingDimensions = embeddingDimensions > 0 ? embeddingDimensions : 384;
        dataDir = blankTo(dataDir, "data");
        githubToken = strip(githubToken);
        tavilyApiKey = strip(tavilyApiKey);
    }

    public boolean hasGuardModel() {
        return !guardModel.isBlank();
    }

    public boolean hasGithubToken() {
        return !githubToken.isBlank();
    }

    public boolean hasTavily() {
        return !tavilyApiKey.isBlank();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
