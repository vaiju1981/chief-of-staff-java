#!/bin/bash
# Stop the agent server and the backend containers.
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ -f logs/.agent.pid ]; then
    PID="$(cat logs/.agent.pid)"
    echo "→ Stopping agent server (PID $PID)..."
    kill "$PID" 2>/dev/null || true
    rm -f logs/.agent.pid
fi

echo "→ Stopping backend containers..."
if ! command -v docker >/dev/null 2>&1; then
    echo "❌ Docker CLI not found; backend containers were not stopped." >&2
    exit 1
fi

DOCKER_INFO_OUTPUT="$(docker info 2>&1)" || {
    echo "❌ Docker/Colima daemon is not reachable; backend containers were not stopped." >&2
    echo "$DOCKER_INFO_OUTPUT" >&2
    echo >&2
    echo "Active Docker context:" >&2
    docker context ls >&2 || true
    exit 1
}

docker compose -f docker/compose.yml down

echo "✅ Stopped. (Data volumes are kept; 'docker compose -f docker/compose.yml down -v' to wipe.)"
