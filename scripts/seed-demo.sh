#!/usr/bin/env bash
# Seeds a running instance with a demo tenant, two templates and a few notifications.
# Usage: BASE_URL=http://localhost:8080 ADMIN_TOKEN=dev-admin-token TENANT=demo scripts/seed-demo.sh
# Writes the tenant API key to .demo-key (git-ignored) and prints it.
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-dev-admin-token}"
TENANT="${TENANT:-demo}"
cd "$(dirname "$0")/.."

key=$(curl -sf -X POST "$BASE_URL/api/v1/admin/api-keys" -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT\",\"name\":\"demo\"}" | sed -E 's/.*"apiKey":"([^"]+)".*/\1/')
echo "$key" > .demo-key

post() { curl -sf -X POST "$BASE_URL/api/v1/$1" -H "X-API-Key: $key" -H 'Content-Type: application/json' "${@:2}" >/dev/null; }
post templates -d '{"name":"welcome","channel":"EMAIL","subject":"Welcome {{name}}","body":"Hi {{name}}, thanks for joining {{product|us}}!"}'
post templates -d '{"name":"otp","channel":"SMS","body":"Your code is {{code}}. It expires in 5 minutes."}'
post notifications -H 'Idempotency-Key: demo-1' -d '{"channels":["EMAIL"],"priority":"HIGH","recipients":{"email":"ann@example.com"},"templateName":"welcome","variables":{"name":"Ann"}}'
post notifications -H 'Idempotency-Key: demo-2' -d '{"channels":["SMS"],"recipients":{"phone":"+15551234567"},"templateName":"otp","variables":{"code":"482913"}}'
post notifications -H 'Idempotency-Key: demo-3' -d '{"channels":["EMAIL"],"priority":"LOW","recipients":{"email":"flaky@example.com"},"body":"This one fails twice and is retried"}'
echo "Seeded tenant '$TENANT'. API key (also in .demo-key): $key"
