#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <version> <output-file>"
  exit 2
fi

VERSION="$1"
OUTPUT_FILE="$2"

if [ ! -f "CHANGELOG.md" ]; then
  echo "CHANGELOG.md が見つかりません"
  exit 1
fi

awk -v version="$VERSION" '
BEGIN {
  in_section = 0
}
$0 ~ "^## \\[" version "\\]" {
  in_section = 1
  next
}
in_section && $0 ~ "^## \\[" {
  exit
}
in_section {
  print
}
' CHANGELOG.md > "$OUTPUT_FILE"

if [ ! -s "$OUTPUT_FILE" ]; then
  echo "CHANGELOG.md にバージョン ${VERSION} のパッチノートがありません"
  exit 1
fi

