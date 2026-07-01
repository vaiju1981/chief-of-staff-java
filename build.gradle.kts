plugins {
    application
}

description = "Chief of Staff — a mostly-local multi-agent assistant, built on java-ai-agent."

group = "io.github.vaiju1981"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.agent.core)
    implementation(libs.agent.langchain4j)
    implementation(libs.agent.tools.annotations)
    implementation(libs.agent.mcp)
    implementation(libs.agent.store.pgvector)
    implementation(libs.agent.store.jdbc)
    implementation(libs.langchain4j.ollama)
    implementation(libs.langchain4j.mcp)
    implementation(libs.tika.core)
    implementation(libs.tika.parsers)
    runtimeOnly(libs.postgresql)
    implementation(libs.spring.boot.web)
    implementation(libs.spring.boot.actuator)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.spring.boot.test)
}

// No toolchain pin: build with whatever JDK runs Gradle (21+), targeting the Java 21 baseline via
// --release 21 — the same approach as java-ai-agent, so it builds on this machine's newer JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    // -parameters: keep record/parameter names at runtime (Spring @ConfigurationProperties binding,
    // and @AgentTool schema derivation in later port steps).
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.vaijanath.chiefofstaff.ChiefOfStaffApplication")
}
