#!/usr/bin/env bash
# Starts infra + app, runs the Playwright suite, then tears everything down.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up -d --wait
mvn -q -DskipTests package
java -jar target/multitenant-notification-service-0.1.0.jar \
  --notification.rate-limit.tenants.e2e-ratelimited.capacity=3 --notification.rate-limit.tenants.e2e-ratelimited.refill-per-second=0.01 --notification.retry.initial-delay=500ms --notification.retry.max-retries=2 > app.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true; docker compose down -v' EXIT
for i in $(seq 1 60); do curl -sf localhost:8080/actuator/health >/dev/null && break; sleep 2; done
(cd e2e && npm install && npx playwright install chromium && npx playwright test)
