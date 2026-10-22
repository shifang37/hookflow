#!/usr/bin/env bash
# End-to-end demo against a locally running HookFlow.
#
#   1. docker compose up -d
#   2. ./mvnw spring-boot:run                                       (terminal 1)
#   3. java -cp target/classes dev.hookflow.tools.MockReceiver 9099 (terminal 2)
#   4. ./scripts/demo.sh
#
# Requires: curl and jq.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RECEIVER_URL="${RECEIVER_URL:-http://127.0.0.1:9099/hook}"
BOOTSTRAP_TOKEN="${HOOKFLOW_BOOTSTRAP_TOKEN:-hookflow-dev-bootstrap-token}"

step() { printf '\n\033[36m=== %s\033[0m\n' "$1"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$1"; }
info() { printf '        \033[90m%s\033[0m\n' "$1"; }

step "0. 健康检查"
curl -fsS "$BASE_URL/actuator/health" >/dev/null
ok "app is up"

step "1. 开租户（拿 API Key，只出现这一次）"
TENANT_NAME="demo-$(date +%s)"
TENANT_JSON=$(curl -fsS -X POST "$BASE_URL/v1/admin/tenants" \
  -H "X-Bootstrap-Token: $BOOTSTRAP_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$TENANT_NAME\",\"rateLimit\":100}")
TENANT_ID=$(echo "$TENANT_JSON" | jq -r .tenantId)
TOKEN=$(echo "$TENANT_JSON" | jq -r .apiKey)
ok "tenantId=$TENANT_ID"
info "apiKey=${TOKEN:0:14}...(truncated)"

step "2. 建 endpoint（拿签名密钥，只出现这一次）"
EP_JSON=$(curl -fsS -X POST "$BASE_URL/v1/endpoints" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"url\":\"$RECEIVER_URL\",\"subscribedTypes\":\"order.paid\",\"maxAttempts\":8}")
ENDPOINT_ID=$(echo "$EP_JSON" | jq -r .id)
ok "endpointId=$ENDPOINT_ID url=$RECEIVER_URL"

step "3. 提交事件"
IDEM_KEY="demo-$(date +%s%N)"
BODY='{"eventType":"order.paid","payload":{"orderId":1001,"amount":99.00,"currency":"CNY"}}'
EVENT_JSON=$(curl -fsS -X POST "$BASE_URL/v1/events" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM_KEY" \
  -H 'Content-Type: application/json' -d "$BODY")
EVENT_ID=$(echo "$EVENT_JSON" | jq -r .eventId)
ok "eventId=$EVENT_ID deliveryCount=$(echo "$EVENT_JSON" | jq -r .deliveryCount)"

step "4. 幂等验证：同一个 Idempotency-Key 再发一次"
AGAIN_JSON=$(curl -fsS -X POST "$BASE_URL/v1/events" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM_KEY" \
  -H 'Content-Type: application/json' -d "$BODY")
AGAIN_ID=$(echo "$AGAIN_JSON" | jq -r .eventId)
if [ "$AGAIN_ID" = "$EVENT_ID" ] && [ "$(echo "$AGAIN_JSON" | jq -r .idempotentReplay)" = "true" ]; then
  ok "返回同一个 eventId=$AGAIN_ID，未创建新事件"
else
  echo "幂等失败" >&2
  exit 1
fi

step "5. 等投递完成"
DELIVERY_ID=""
STATUS=""
for _ in $(seq 1 90); do
  DELIVERY_ID=$(curl -fsS "$BASE_URL/v1/deliveries?eventId=$EVENT_ID" \
    -H "Authorization: Bearer $TOKEN" | jq -r '.items[0].id // empty')
  if [ -n "$DELIVERY_ID" ]; then
    STATUS=$(curl -fsS "$BASE_URL/v1/deliveries/$DELIVERY_ID" -H "Authorization: Bearer $TOKEN" \
      | jq -r '.delivery.status')
    case "$STATUS" in SUCCEEDED|DEAD) break ;; esac
  fi
  sleep 0.5
done

printf '\n    \033[33mdelivery #%s : %s\033[0m\n' "$DELIVERY_ID" "$STATUS"
curl -fsS "$BASE_URL/v1/deliveries/$DELIVERY_ID" -H "Authorization: Bearer $TOKEN" \
  | jq -r '.attempts[] | "      attempt \(.attemptNo): \(.status) status=\(.statusCode) error=\(.errorClass) \(.durationMs)ms"'

step "6. 接收方视角（含签名校验结果）"
curl -fsS "${RECEIVER_URL%/hook}/received" | jq -r '.items[:3][]' || echo "    接收方不可达"

printf '\n\033[36m演示完成。\033[0m\n'
echo "  让接收方返回 500，再提交事件，观察重试阶梯："
echo "    curl '${RECEIVER_URL}?status=500'"
echo "  修好后重放："
echo "    curl -X POST $BASE_URL/v1/deliveries/$DELIVERY_ID/replay -H 'Authorization: Bearer $TOKEN'"
