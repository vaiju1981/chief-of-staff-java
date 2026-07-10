# chief-of-staff-java

> A mostly-local, multi-agent personal AI assistant — a Java port of
> [chief-of-staff](https://github.com/vaiju1981/chief-of-staff), built on
> [java-ai-agent](https://github.com/vaiju1981/java-ai-agent) with Ollama.
>
> Each agent is exposed as an **OpenAI-compatible model** (`agent-<name>`), so you chat with it in
> Open WebUI or any OpenAI client. A supervisor routes your request to the right specialist.

- **Chat model:** `gemma4:31b-cloud` (Ollama cloud model). Runs on Ollama's servers, so chat is *not*
  fully local — embeddings, RAG, and transcription stay on your machine.
- **Embeddings:** `granite-embedding:30m` (local), for RAG.
- **Safety (optional):** `llama-guard3:1b` via the `Guard` component (`COS_GUARD_MODEL`), fail-open.

## What it does

A supervisor (`agent-chief-of-staff`) classifies each turn and delegates to a specialist, or answers
meta questions about the system itself. Specialists are exposed as their own models too, so you can
talk to one directly.

| Agent model | Role |
|---|---|
| `agent-chief-of-staff` | Supervisor: routes to a specialist, or answers "what can you do?" / "how do I record a meeting?" |
| `agent-comms` | Writing from known info — emails, messages, announcements, long-form articles/essays |
| `agent-code` | Programming Q&A, debugging, and (with `GITHUB_TOKEN`) GitHub issue management |
| `agent-researcher` | Indexed-document RAG + filesystem read + web search (Tavily, when configured) |
| `agent-notes` | Explore and search the notes vault (meetings, projects, daily notes) |
| `agent-handoff` | Builds a rich prompt to paste into Claude.ai / ChatGPT for the heaviest tasks |
| `agent-meeting` | Pilots a live meeting recording (start / stop / status) from chat |
| `agent-report` | Research → verify → write: a web-grounded, cited long-form report |

### Report pipeline

`agent-report` runs a three-stage pipeline: a **researcher** gathers cited findings (web + your
documents), a **verifier** adversarially re-checks each claim against its source and upgrades secondary
citations to primary (skipped when web tools aren't configured), then a **writer** composes the grounded
report. This is the one path that does web search *and* a long report, and it streams.

### RAG

Documents dropped into `data/library/<category>/` (categories: `idn`, `research`, `personal`, `admin`,
`inbox`) are auto-ingested by a poller (Apache Tika for PDF/DOCX/PPTX/HTML, plain read for text). Chunks
are stored in pgvector with a **grounding gate** (`min-score` cosine floor): weak matches are dropped and
the agent is told the topic isn't in your documents, so it won't invent answers. Deleting or renaming a
library file prunes its embeddings.

### Meetings

`agent-meeting` captures your microphone + BlackHole (the other participants' audio), mixes them to a
WAV, then (on stop) transcribes with whisper.cpp, summarizes via the LLM, saves a structured note to the
vault, and indexes it for search. A silence/noise guard prevents summarizing empty audio. See
`/meeting/devices` to confirm BlackHole is visible.

### Safety

When `COS_GUARD_MODEL` points at a Llama Guard model you've pulled into Ollama, every user message is
checked before it reaches an agent and every reply is checked before it is returned. Fail-open: if the
guard model is unreachable, the request proceeds and the failure is logged.

### Observability

- `/health` reports the real state of Ollama (reachability) and the pgvector store (up / degraded),
  not just a flat "ok".
- The OpenAI `usage` field reports token counts (real when the model reports them, else a char-based
  estimate).
- Optional bearer-token auth: set `COS_API_KEY` and clients must send `Authorization: Bearer <key>`
  (Open WebUI → "API Key" in the model settings). `/health` stays open.

## Requirements

- JDK 21+
- [Ollama](https://ollama.com) running, with the models pulled:
  `ollama pull gemma4:31b-cloud` and `ollama pull granite-embedding:30m`
- For RAG: Postgres + pgvector (provided by `docker/compose.yml` via `./start.sh`)
- For meetings: [BlackHole 2ch](https://github.com/ExistentialAudio/BlackHole) and `whisper.cpp`
- For web search / GitHub tools: a Tavily API key and/or a GitHub token (optional)

## Run

### Local (agents only, no RAG/meetings DB)

```bash
./gradlew run          # agent server on http://localhost:8002
```

### Full stack (Postgres + Open WebUI + agent server)

```bash
./start.sh             # starts Postgres + Open WebUI, builds, runs the agent server
./stop.sh              # stops everything
```

Smoke-test the OpenAI surface:

```bash
curl -s http://localhost:8002/v1/models | jq
curl -s http://localhost:8002/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"agent-comms","messages":[{"role":"user","content":"Draft a one-line note cancelling tomorrow'\''s standup."}]}' | jq
```

Point Open WebUI at `http://localhost:8002` and pick **agent-chief-of-staff** (or any specialist) in the
model list.

## Configuration

Everything is overridable by env var (see `src/main/resources/application.yml`). `.env` (gitignored,
see `.env.template`) is sourced by `start.sh`.

| Env | Default | Meaning |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `COS_MODEL` | `gemma4:31b-cloud` | chat model |
| `COS_EMBEDDING_MODEL` | `granite-embedding:30m` | RAG embeddings |
| `COS_EMBEDDING_DIMENSIONS` | `384` | embedding vector size |
| `COS_NUM_CTX` | `262144` | Ollama context window (long outputs) |
| `COS_MIN_SCORE` | `0.3` | RAG grounding gate (cosine floor) |
| `COS_GUARD_MODEL` | _(blank)_ | Llama Guard model for input/output safety; blank = disabled |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/chief_of_staff` | pgvector DB |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `cos` / `cos_local_dev` | pgvector credentials |
| `COS_DATA_DIR` | `data` | root for the MCP filesystem server + RAG library/vault |
| `GITHUB_TOKEN` | _(blank)_ | enables the code agent's GitHub issue tools |
| `TAVILY_API_KEY` | _(blank)_ | enables the researcher's web search |
| `COS_WHISPER_CLI` / `COS_WHISPER_MODEL` / `COS_WHISPER_LANGUAGE` | `whisper-cli` / `models/whisper/ggml-base.bin` / `auto` | whisper.cpp pipeline |
| `COS_API_KEY` | _(blank)_ | if set, require `Bearer` auth on chat endpoints |

## Project layout

```
src/main/java/dev/vaijanath/chiefofstaff/
  agent/      ChatAgent seam, Supervisor, GenerationAgent (long-form + auto-continue), ToolChatAgent,
              ReportAgent (research→verify→write), Handoff
  api/        ChatController (OpenAI surface), HealthController
  config/     AgentConfig (wiring), CosProperties, McpToolSource, Guard, ApiKeyFilter,
              UsageTracker + metered model ports
  rag/        RagStore (pgvector + grounding gate), RagTools, LibraryWatcher, TextExtraction
  meeting/    MeetingRecorder, MeetingProcess, MeetingTools, MeetingController
  prompt/     System + routing prompts
```

## License

MIT.
