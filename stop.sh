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
docker compose -f docker/compose.yml down

echo "✅ Stopped. (Data volumes are kept; 'docker compose -f docker/compose.yml down -v' to wipe.)"
