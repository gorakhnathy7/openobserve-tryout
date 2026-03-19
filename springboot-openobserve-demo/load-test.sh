#!/bin/bash
# Load generator script - generates traffic to produce logs and traces in OpenObserve

BASE_URL="http://localhost:8080/api/orders"
echo "Starting load test against $BASE_URL..."
echo "Press Ctrl+C to stop."
echo ""

i=1
while true; do
    echo "--- Iteration $i ---"

    # Fetch an order (GET)
    echo "GET /api/orders/ORD-$RANDOM"
    curl -s "$BASE_URL/ORD-$(printf '%05d' $RANDOM)" | python3 -m json.tool 2>/dev/null || echo "(response)"

    # Create an order (POST)
    echo "POST /api/orders"
    curl -s -X POST "$BASE_URL" \
        -H "Content-Type: application/json" \
        -d "{\"productId\": \"PROD-00$((RANDOM % 5 + 1))\", \"quantity\": $((RANDOM % 5 + 1)), \"customerId\": \"CUST-$RANDOM\"}" \
        | python3 -m json.tool 2>/dev/null || echo "(response)"

    # Occasionally simulate an error (every 5 iterations)
    if (( i % 5 == 0 )); then
        echo "GET /api/orders/simulate-error"
        curl -s "$BASE_URL/simulate-error"
        echo ""
    fi

    echo ""
    i=$((i + 1))
    sleep 2
done
