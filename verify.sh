#!/usr/bin/env bash

set -e

echo "Running Simplify Money Ledger verification..."

echo "1. Running unit tests..."
./mvnw test

echo "2. Checking ledger output..."
test -f ledger.json

echo "3. Checking summary output..."
test -f summary.json

echo "4. Checking reconciliation output..."
test -f reconciliation.json

echo "5. Validating JSON..."

python3 - <<'PY'
import json

for filename in [
    "ledger.json",
    "summary.json",
    "reconciliation.json"
]:
    try:
        with open(filename, "r") as f:
            json.load(f)
        print(f"PASS: {filename}")
    except Exception as e:
        print(f"FAIL: {filename}: {e}")
        raise SystemExit(1)
PY

echo ""
echo "All verification checks passed."