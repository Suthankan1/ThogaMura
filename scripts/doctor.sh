#!/usr/bin/env bash
set -u

echo "ThogaMura starter environment check"
echo
for program in java javac mvn node npm docker git; do
  if command -v "$program" >/dev/null 2>&1; then
    printf '[OK] %s: %s\n' "$program" "$(command -v "$program")"
  else
    printf '[MISSING] %s\n' "$program"
  fi
done
echo
test -f backend/pom.xml || { echo "Run this script from ThogaMura project root."; exit 1; }
test -f frontend/package-lock.json || { echo "Missing frontend lockfile."; exit 1; }
echo "Structure present. This does not prove the application or networking is complete."
