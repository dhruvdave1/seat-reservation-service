#!/usr/bin/env sh
# One command: hot-seat burst plus reconciliation.
#   BASE_URL=https://... ADMIN_KEY=... scripts/burst.sh [--requests 20000 --concurrency 500 ...]
# Uses a local JDK 25 if there is one, otherwise runs inside the eclipse-temurin:25 image.
set -eu
here=$(cd "$(dirname "$0")" && pwd)

if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])'; then
  exec java "$here/Burst.java" "$@"
fi

# In Docker, "localhost" is the container itself; reach the host instead.
url=${BASE_URL:-http://localhost:8080}
url=$(printf '%s' "$url" | sed 's#://localhost#://host.docker.internal#; s#://127.0.0.1#://host.docker.internal#')
exec docker run --rm -e BASE_URL="$url" -e ADMIN_KEY="${ADMIN_KEY:-}" \
  --add-host=host.docker.internal:host-gateway \
  -v "$here:/scripts:ro" eclipse-temurin:25-jdk java /scripts/Burst.java "$@"
