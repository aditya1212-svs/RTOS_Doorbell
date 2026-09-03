# TaskBoard — shared task board for agent sync

A tiny, **zero-dependency** task board so multiple agents (or people) can
create, assign, and update a shared task list in sync. It runs standalone and
does **not** touch the Spring Boot backend (which stays on port 8080).

Tasks are shared through a REST API and persisted to `taskboard/data/tasks.json`,
so agents on the same machine see exactly the same board even across restarts.

## Run it

```bash
cd taskboard
node server.js
# or:  PORT=8081 node server.js   (any port you like)
```

Open the UI in your browser: **http://localhost:8081**

## How agents work in sync

Agents don't need a browser — they just hit the REST API. The board records
**who** created/updated each task via the optional `X-Agent` header, so the whole
team can see progress at a glance.

| Action | Command (from project root) |
| ------ | --------------------------- |
| **List tasks** | `curl localhost:8081/api/tasks` |
| **Create** | `curl -X POST localhost:8081/api/tasks -H 'Content-Type: application/json' -H 'X-Agent: backend-agent' -d '{"title":"Add auth","assignee":"backend-agent"}'` |
| **Claim / start** | `curl -X PATCH localhost:8081/api/tasks/TASK-001 -H 'Content-Type: application/json' -H 'X-Agent: frontend-agent' -d '{"status":"in_progress","assignee":"frontend-agent"}'` |
| **Block** | `curl -X PATCH localhost:8081/api/tasks/TASK-001 -H 'Content-Type: application/json' -H 'X-Agent: rel-agent' -d '{"status":"blocked","assignee":"rel-agent"}'` |
| **Complete** | `curl -X PATCH localhost:8081/api/tasks/TASK-001 -H 'Content-Type: application/json' -H 'X-Agent: backend-agent' -d '{"status":"completed"}'` |
| **Delete** | `curl -X DELETE localhost:8081/api/tasks/TASK-001` |
| **Status summary** | `curl localhost:8081/api/statuses` |
| **Health** | `curl localhost:8081/api/health` |

An optional `dependsOn: ["TASK-001"]` field marks dependencies so agents know
the order to work in.

## Chat — agents can message each other

The board also has a team chat tab (💬 Chat) and a simple messaging API. Messages
are persisted to `taskboard/data/messages.json`. Your identity is the
`X-Agent` header — always send it so teammates know who's talking.

| Action | Command |
| ------ | ------- |
| **Read messages** | `curl localhost:8081/api/messages` |
| **Send (to everyone)** | `curl -X POST localhost:8081/api/messages -H 'Content-Type: application/json' -H 'X-Agent: backend-agent' -d '{"text":"recognition is done"}'` |
| **Send (direct)** | `curl -X POST localhost:8081/api/messages -H 'Content-Type: application/json' -H 'X-Agent: frontend-agent' -d '{"text":"can you review auth?","to":"backend-agent"}'` |
| **List known agents** | `curl localhost:8081/api/agents` |
| **Clear chat** | `curl -X DELETE localhost:8081/api/messages` |

## Task fields

- `id` — auto-generated `TASK-###`
- `title` — required
- `description` — optional
- `assignee` — optional agent id
- `status` — `pending | in_progress | blocked | completed` (default `pending`)
- `dependsOn` — optional array of task ids
- `createdBy` / `updatedBy` — from the `X-Agent` header
- `createdAt` / `updatedAt` — ISO timestamps

## API notes

- All JSON endpoints live under `/api`. Errors return `{ "error": "..." }` with 4xx/5xx.
- Example payloads are above; the UI at `/` does everything interactively and polls every 3s.