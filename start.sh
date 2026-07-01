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
if ! docker info >/dev/null 2>&1; then
    echo "❌ No Docker/Colima daemon. Start it (e.g. 'colima start') and re-run." >&2
    exit 1
fi
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
