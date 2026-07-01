# chief-of-staff-java

> A mostly-local, multi-agent personal AI assistant — a Java port of
> [chief-of-staff](https://github.com/vaiju1981/chief-of-staff), built on
> [java-ai-agent](https://github.com/vaiju1981/java-ai-agent) with Ollama.

Each agent is exposed as an **OpenAI-compatible model** (`agent-<name>`), so you chat with it in
Open WebUI or any OpenAI client. A supervisor routes your request to the right specialist.

- **Chat model:** `gemma4:31b-cloud` (Ollama cloud model, replacing granite). Runs on Ollama's
  servers, so chat is *not* fully local — embeddings and everything else stay on your machine.
- **Embeddings:** `granite-embedding:30m` (local), for RAG.
- **Safety:** `llama-guard3:1b` via `Trust.govern` (added in a later step).

## Status — scaffold

This repo currently ships the **vertical slice**: the tool-less **Comms** agent, reachable through
the OpenAI endpoint. It proves the whole pipe (Open WebUI → Spring Boot → java-ai-agent → Ollama).

| Step | Scope | State |
|---|---|---|
| 1–3 | Gradle + Spring Boot + OpenAI endpoint + **Comms** agent | ✅ this scaffold |
| 4 | Supervisor router (`StructuredOutput`) + language detection | ⬜ |
| 5 | Tool agents: researcher, notes, code, meeting (`@AgentTool` + `agent-mcp`) | ⬜ |
| 6 | RAG ingestion + watcher (Tika + `agent-store-pgvector`) | ⬜ |
| 7 | Meeting pipeline (Java Sound + whisper.cpp subprocess) | ⬜ |
| 8 | Docker compose (Postgres+pgvector, Open WebUI) + start/stop + full README | ⬜ |

## Requirements

- JDK 21+
- [Ollama](https://ollama.com) running, with the models pulled:
  `ollama pull gemma4:31b-cloud` and `ollama pull granite-embedding:30m`

## Run

```bash
# starts the agent server on http://localhost:8002
./gradlew run
```

Smoke-test the OpenAI surface:

```bash
curl -s http://localhost:8002/v1/models | jq
curl -s http://localhost:8002/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"agent-comms","messages":[{"role":"user","content":"Draft a one-line note cancelling tomorrow'\''s standup."}]}' | jq
```

Point Open WebUI at `http://localhost:8002` and pick **agent-comms** in the model list.

## Configuration

Everything is overridable by env var (see `src/main/resources/application.yml`):

| Env | Default | Meaning |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `COS_MODEL` | `gemma4:31b-cloud` | chat model |
| `COS_EMBEDDING_MODEL` | `granite-embedding:30m` | RAG embeddings |
| `COS_GUARD_MODEL` | `llama-guard3:1b` | Llama Guard safety model |
| `COS_PORT` | `8002` | HTTP port |

## License

MIT.
