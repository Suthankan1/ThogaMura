#!/usr/bin/env bash
# ==============================================================================
# ThogaMura Disposable Fixture Verification (QA-06)
# Verifies the full end-to-end stock workflow across the live network:
#   Reserve (POST /requests) -> Pick (POST /pick) -> Dispatch (POST /dispatch) -> Audit Movement
# ==============================================================================
set -euo pipefail

GATEWAY_URL="${THOGAMURA_GATEWAY_URL:-http://127.0.0.1:8080}"
SKU="ITEM-007"
QTY=1

BOLD='\033[1m'
GREEN='\033[0;32m'
RED='\033[0;31m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BOLD}${CYAN}==================================================================${NC}"
echo -e "${BOLD}${CYAN}  Verifying Full Stock Workflow: Reserve → Pick → Dispatch → Audit ${NC}"
echo -e "${BOLD}${CYAN}==================================================================${NC}"

# Check gateway availability
if ! curl -s -f -o /dev/null "${GATEWAY_URL}/actuator/health" 2>/dev/null; then
    echo -e "${RED}[ERROR] API Gateway is not reachable at ${GATEWAY_URL}.${NC}"
    echo "Please ensure ThogaMura is running ('./start.sh') before executing this verification."
    exit 1
fi

# Generate UUID v4 for the disposable transaction
if command -v uuidgen >/dev/null 2>&1; then
    REQ_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
else
    REQ_ID=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
fi

echo -e "Disposable Request ID: ${YELLOW}${REQ_ID}${NC}"
echo -e "Target Product SKU:    ${YELLOW}${SKU}${NC}"
echo -e "Quantity to Reserve:   ${YELLOW}${QTY}${NC}"
echo "------------------------------------------------------------------"

# STEP 1: Reserve Stock
echo -e "\n${BOLD}[1/4] Submitting Stock Reservation (POST /api/v1/requests)...${NC}"
RESERVE_RESP=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${GATEWAY_URL}/api/v1/requests" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${REQ_ID}" \
    -d "{\"sku\":\"${SKU}\",\"quantity\":${QTY}}")

HTTP_STATUS=$(echo "$RESERVE_RESP" | grep "HTTP_STATUS:" | cut -d':' -f2)
BODY=$(echo "$RESERVE_RESP" | sed '/HTTP_STATUS:/d')

if [ "$HTTP_STATUS" != "201" ]; then
    echo -e "${RED}[FAIL] Reservation failed with HTTP ${HTTP_STATUS}:${NC} $BODY"
    exit 1
fi

STATUS=$(echo "$BODY" | python3 -c "import sys, json; print(json.load(sys.stdin).get('status',''))")
if [ "$STATUS" != "ACCEPTED" ]; then
    echo -e "${RED}[FAIL] Expected status ACCEPTED, got:${NC} $STATUS"
    exit 1
fi
echo -e "${GREEN}✓ Reservation accepted: Status = ACCEPTED (PostgreSQL balance reserved)${NC}"

# STEP 2: Mark Picked
echo -e "\n${BOLD}[2/4] Picker Action: Marking as Picked (POST /api/v1/requests/${REQ_ID}/pick)...${NC}"
PICK_RESP=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${GATEWAY_URL}/api/v1/requests/${REQ_ID}/pick")
HTTP_STATUS=$(echo "$PICK_RESP" | grep "HTTP_STATUS:" | cut -d':' -f2)
BODY=$(echo "$PICK_RESP" | sed '/HTTP_STATUS:/d')

if [ "$HTTP_STATUS" != "200" ]; then
    echo -e "${RED}[FAIL] Pick transition failed with HTTP ${HTTP_STATUS}:${NC} $BODY"
    exit 1
fi

STATUS=$(echo "$BODY" | python3 -c "import sys, json; print(json.load(sys.stdin).get('status',''))")
if [ "$STATUS" != "PICKED" ]; then
    echo -e "${RED}[FAIL] Expected status PICKED, got:${NC} $STATUS"
    exit 1
fi
echo -e "${GREEN}✓ Order picked: Status = PICKED${NC}"

# STEP 3: Mark Dispatched
echo -e "\n${BOLD}[3/4] Dispatcher Action: Marking as Dispatched (POST /api/v1/requests/${REQ_ID}/dispatch)...${NC}"
DISPATCH_RESP=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${GATEWAY_URL}/api/v1/requests/${REQ_ID}/dispatch")
HTTP_STATUS=$(echo "$DISPATCH_RESP" | grep "HTTP_STATUS:" | cut -d':' -f2)
BODY=$(echo "$DISPATCH_RESP" | sed '/HTTP_STATUS:/d')

if [ "$HTTP_STATUS" != "200" ]; then
    echo -e "${RED}[FAIL] Dispatch transition failed with HTTP ${HTTP_STATUS}:${NC} $BODY"
    exit 1
fi

STATUS=$(echo "$BODY" | python3 -c "import sys, json; print(json.load(sys.stdin).get('status',''))")
if [ "$STATUS" != "DISPATCHED" ]; then
    echo -e "${RED}[FAIL] Expected status DISPATCHED, got:${NC} $STATUS"
    exit 1
fi
echo -e "${GREEN}✓ Order dispatched: Status = DISPATCHED (on_hand and reserved decremented)${NC}"

# STEP 4: Verify Immutable Stock Movement Audit Record
echo -e "\n${BOLD}[4/4] Verifying Immutable Movement Audit Trail (GET /api/v1/movements)...${NC}"
MOVEMENTS_JSON=$(curl -s "${GATEWAY_URL}/api/v1/movements")
HAS_MOVEMENT=$(echo "$MOVEMENTS_JSON" | python3 -c "
import sys, json
movements = json.load(sys.stdin)
matches = [m for m in movements if m.get('stockRequestId') == '${REQ_ID}']
if matches:
    m = matches[0]
    print(f\"FOUND id={m.get('id')} delta={m.get('quantityDelta')} reason={m.get('reason')}\")
else:
    print('NOT_FOUND')
")

if [[ "$HAS_MOVEMENT" == NOT_FOUND* ]]; then
    echo -e "${RED}[FAIL] Audit movement record not found for request ${REQ_ID}.${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Audit movement verified: ${HAS_MOVEMENT}${NC}"

echo -e "\n${BOLD}${GREEN}==================================================================${NC}"
echo -e "${BOLD}${GREEN}  ✓ Complete Workflow Verified Successfully!                      ${NC}"
echo -e "${BOLD}${GREEN}    Reserve (ACCEPTED) → Pick (PICKED) → Dispatch (DISPATCHED)     ${NC}"
echo -e "${BOLD}${GREEN}    → Immutable Movement Logged                                    ${NC}"
echo -e "${BOLD}${GREEN}==================================================================${NC}"
