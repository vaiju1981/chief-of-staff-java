# Creator Mode — Automated Notes Generator

> Personal "content creator" mode: given an *idea*, the agent autonomously researches via a suite of
> retrieval tools, reads the real content (text **and** images), and synthesizes a structured, cited
> note that is saved to the vault and indexed for later search.

Status: **P0 + P1 + P2 implemented** — creator agent wired, routes, persists notes; images are sourced,
size-capped, extension-sniffed from content-type, and grounded on save (missing `assets/` refs dropped).
P3 (arxiv / medium / youtube sources) remains.

## Decisions (locked)
- **Images:** sourced only — `fetch_image` pulls real figures from retrieved pages/papers. No AI image
  generation (no extra model/key). The agent may only embed image paths returned by `fetch_image`.
- **Sources:** web search (Tavily), Arxiv papers, Medium (scoped web search), YouTube transcripts, and
  the user's local PDFs. Anything else (WebMD, etc.) is covered generically: `web_search` returns result
  links, and `read_page` deep-reads any of those links into text + image URLs.
- **Output:** notes saved to `data/vault/creator/` with an `assets/` subfolder for images, and indexed
  in pgvector (type `creator`) so the Notes agent can find them later.

## Core retrieval primitive
Search tools return *links*; the agent must be able to **deep-read those links**. So the foundation is a
generic `read_page(url)` that fetches a URL, extracts clean text, and discovers image URLs — every
source-specific search just feeds URLs into it.

## Tool set (`agent/CreatorTools.java`) — plain `@AgentTool` Java methods (no npx/MCP)
| Tool | Source | Notes |
|---|---|---|
| `web_search(query)` | Tavily (MCP, already wired) | returns result links + snippets |
| `read_page(url)` | **generic** | fetches page → cleaned text + discovered `<img>` URLs (the workhorse) |
| `search_arxiv(query)` | Arxiv API | papers + abstracts → feeds `read_page` for full PDFs |
| `medium_search(query)` | scoped web search (`site:medium.com`) | → `read_page` |
| `youtube_transcript(url)` | `yt-dlp` subprocess | transcript text (same pattern as whisper.cpp) |
| `read_pdf(url_or_path)` | Tika (`TextExtraction`) | local vault PDFs + downloaded papers |
| `fetch_image(url, name)` | download | → `vault/creator/assets/<note>/`, returns relative path (grounds images) |
| `save_note(title, markdown)` | — | write to `vault/creator/`, index via `RagStore` (type `creator`) |

## Agent + prompt
- `CosPrompts.creator()` defines the autonomous loop and the note schema:
  `# Title`, `> TL;DR`, `## Key points` (cited), `## Detail` (sourced quotes),
  `## Images` (figures w/ captions + source), `## References`, `## Next steps`.
  Same "cite or it didn't happen" grounding rule as `researcher`.
- Registered as a `ToolChatAgent` (tool-using `DefaultAgent`) with a per-tool allow-list
  (mirrors `AgentConfig.toolAgent`). Streams; multi-step (`maxSteps`) so it can
  search → read → fetch images → write.
- Image rule: agent may **only** embed paths returned by `fetch_image` (no hallucinated URLs).
- Supervisor: add `creator` to `SupervisorPrompts.CATALOG` so "make a note on…", "content mode",
  "research and save a note" route to `agent-creator`.

## Data flow
```
idea → Creator (tool loop)
  web_search / search_arxiv / medium_search → URLs
  read_page / read_pdf / youtube_transcript → text + image URLs
  fetch_image → vault/creator/assets/<note>/
  save_note → vault/creator/<title>.md + RagStore.ingest(type="creator")
→ note shown in chat AND persisted (searchable later by Notes agent)
```

## Phasing
- **P0** — `CosPrompts.creator()` + schema; `CreatorTools`: `web_search` (via MCP Tavily), `read_page`,
  `read_pdf`, `save_note`, `fetch_image`.
- **P1** — wire `agent-creator` in `AgentConfig`, supervisor route, save+index, stream test.
- **P2** — image enrichment: `fetch_image` downloads to `assets/`, grounded embedding in the note.
- **P3** — `search_arxiv`, `medium_search`, `youtube_transcript`; citation/reference polish;
  unit + integration tests.

## Files touched
- New: `agent/CreatorTools.java`; prompt in `CosPrompts.creator()`.
- Edit: `config/AgentConfig.java` (register + route + tools), `prompt/SupervisorPrompts.java` (catalog).
- Reuse: `RagStore.ingest`, `TextExtraction` (Tika), `MeetingProcess` save/index pattern, `ToolChatAgent`.
