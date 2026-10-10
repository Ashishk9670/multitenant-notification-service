#!/usr/bin/env bash
# End-to-end smoke test against a running instance: issue a key, send, wait for SENT, check metrics.
# Usage: BASE_URL=http://localhost:8080 ADMIN_TOKEN=... scripts/smoke.sh
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-dev-admin-token}"
tenant="smoke-$RANDOM"

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/notifications" -H 'Content-Type: application/json' \
  -H "X-Tenant-Id: $tenant" -d '{}')
[ "$code" = 401 ] || { echo "expected 401 without API key, got $code"; exit 1; }

key=$(curl -sf -X POST "$BASE_URL/api/v1/admin/api-keys" -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$tenant\",\"name\":\"smoke\"}" | sed -E 's/.*"apiKey":"([^"]+)".*/\1/')
id=$(curl -sf -X POST "$BASE_URL/api/v1/notifications" -H "X-API-Key: $key" -H 'Content-Type: application/json' \
  -d '{"channels":["EMAIL"],"recipients":{"email":"smoke@example.com"},"body":"hello"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "accepted $id"
for i in $(seq 1 60); do
  st=$(curl -sf "$BASE_URL/api/v1/notifications/$id" -H "X-API-Key: $key" || true)
  echo "$st" | grep -q '"status":"SENT"' && break
  sleep 1
done
echo "$st" | grep -q '"status":"SENT"' || { echo "not delivered: $st"; exit 1; }
# Capture first: with pipefail, `curl | grep -q` can fail via SIGPIPE; also allow the counter a moment to appear.
for i in $(seq 1 15); do
  metrics=$(curl -sf "$BASE_URL/actuator/prometheus" || true)
  echo "$metrics" | grep -q "notifications_sent_total.*tenant=\"$tenant\"" && break
  sleep 1
done
echo "$metrics" | grep -q "notifications_sent_total.*tenant=\"$tenant\"" || { echo "metric missing"; exit 1; }
echo "smoke OK"
