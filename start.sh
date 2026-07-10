#!/bin/bash
# Start the chief-of-staff-java stack: backends (Postgres + Open WebUI) then the agent server.
# Usage: ./start.sh
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load .env (tokens + DB password) if present, so both compose and the app see them.
if [ -f .env ]; then
    set -a; . ./.env; set +a
fi

echo "→ Starting backends (Postgres + Open WebUI)..."
if ! command -v docker >/dev/null 2>&1; then
    echo "❌ Docker CLI not found. Install Docker or Colima and re-run." >&2
    exit 1
fi

DOCKER_INFO_OUTPUT="$(docker info 2>&1)" || {
    echo "❌ Docker/Colima daemon is not reachable." >&2
    echo "$DOCKER_INFO_OUTPUT" >&2
    echo >&2
    echo "Active Docker context:" >&2
    docker context ls >&2 || true
    echo >&2
    echo "If you use Colima, try:" >&2
    echo "  colima status" >&2
    echo "  colima start" >&2
    echo "  docker context use colima" >&2
    exit 1
}
docker compose -f docker/compose.yml up -d

echo "→ Building + starting the agent server on :8002..."
./gradlew installDist -q
mkdir -p logs
nohup build/install/chief-of-staff-java/bin/chief-of-staff-java > logs/agent.log 2>&1 &
echo $! > logs/.agent.pid
echo "   agent PID $(cat logs/.agent.pid) (logs: logs/agent.log)"

echo "→ Waiting for the agent server..."
for i in $(seq 1 30); do
    if curl -s -m 2 http://localhost:8002/health >/dev/null 2>&1; then
        echo "✅ Up. Open http://localhost:3000 and pick agent-chief-of-staff."
        exit 0
    fi
    sleep 1
done
echo "⚠️  Agent server didn't respond in time — check logs/agent.log" >&2
