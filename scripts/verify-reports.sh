#!/usr/bin/env bash
# Fails if the Testcontainers integration tests were skipped (e.g. Docker missing) or did not run.
# Usage: scripts/verify-reports.sh <min-tests>
set -euo pipefail
min="${1:-1}"
dir="target/failsafe-reports"
shopt -s nullglob
files=("$dir"/*IT.txt)
[ ${#files[@]} -gt 0 ] || { echo "No failsafe IT reports found in $dir"; exit 1; }
total=0; skipped=0
for f in "${files[@]}"; do
  line=$(grep -m1 "Tests run:" "$f")
  echo "$f: $line"
  t=$(sed -E 's/.*Tests run: ([0-9]+).*/\1/' <<<"$line")
  s=$(sed -E 's/.*Skipped: ([0-9]+).*/\1/' <<<"$line")
  total=$((total + t)); skipped=$((skipped + s))
done
echo "integration tests run=$total skipped=$skipped (required >= $min, skipped = 0)"
[ "$skipped" -eq 0 ] && [ "$total" -ge "$min" ]
