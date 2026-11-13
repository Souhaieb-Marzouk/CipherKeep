#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
dir=${1:-"$root/release-assets"}
pub=${MINISIGN_PUBLIC_KEY:-"$root/release/minisign.pub"}
checks="$dir/checksums.txt"
sig="$dir/checksums.txt.minisig"

test -d "$dir"
test -s "$checks"
test -s "$sig"
test -s "$pub"

echo "== checksum files =="
(
  cd "$dir"
  sha256sum --check --strict checksums.txt
)

echo "== Minisign signature =="
minisign -V -p "$pub" -m "$checks" -x "$sig"

echo "== artifact coverage =="
while IFS= read -r line; do
  name=${line#*  }
  test -f "$dir/$name" || { echo "missing artifact: $name" >&2; exit 1; }
done < "$checks"

if command -v slsa-verifier >/dev/null 2>&1; then
  provenance="$dir/provenance.intoto.jsonl"
  if [ -f "$provenance" ]; then
    echo "== SLSA provenance =="
    while IFS= read -r line; do
      name=${line#*  }
      slsa-verifier verify-artifact \
        --provenance "$provenance" \
        --source-uri github.com/Souhaieb-Marzouk/CipherKeep \
        --artifact-path "$dir/$name"
    done < "$checks"
  else
    echo "provenance.intoto.jsonl not present; skipped" >&2
  fi
else
  echo "slsa-verifier not installed; checksum and Minisign checks passed" >&2
fi

echo "release verification: PASS"
