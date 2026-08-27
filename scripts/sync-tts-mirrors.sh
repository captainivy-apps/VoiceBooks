#!/usr/bin/env bash
set -euo pipefail

REMOTE="root@es.galaxyrover.com"
REMOTE_DIR="/root/src.galaxyrover.com/statics/mirrors"
MIRROR_BASE="https://www.galaxyrover.com/mirrors"
GITHUB_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
WORKDIR="${TMPDIR:-/tmp}/tts-mirrors"

MODELS=(
  "vits-piper-zh_CN-xiao_ya-medium.tar.bz2"
  "vits-piper-en_US-amy-low.tar.bz2"
)

mkdir -p "$WORKDIR"

echo "==> Downloading from GitHub"
for file in "${MODELS[@]}"; do
  echo "  $file"
  curl -L --fail -o "$WORKDIR/$file" "$GITHUB_BASE/$file"
done

echo "==> Uploading to $REMOTE:$REMOTE_DIR"
ssh "$REMOTE" "mkdir -p $REMOTE_DIR"
scp "${MODELS[@]/#/$WORKDIR/}" "$REMOTE:$REMOTE_DIR/"

echo "==> Verifying mirror URLs"
for file in "${MODELS[@]}"; do
  local_size=$(stat -f%z "$WORKDIR/$file" 2>/dev/null || stat -c%s "$WORKDIR/$file")
  remote_size=$(curl -sI "$MIRROR_BASE/$file" | awk 'tolower($1)=="content-length:" {print $2}' | tr -d '\r')
  if [[ "$local_size" != "$remote_size" ]]; then
    echo "FAIL $file: local=$local_size remote=$remote_size"
    exit 1
  fi
  echo "OK   $file ($local_size bytes)"
done

echo "Done."
