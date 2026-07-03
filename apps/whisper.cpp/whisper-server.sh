#!/bin/bash

PRJ_DIR="$(cd $(dirname $0);pwd)"
WHISPER_BIN="$PRJ_DIR/whisper-1.9.1-coreML-bin"
export PATH="$WHISPER_BIN:$PATH"

whisper-server --port 8766 -m models/ggml-small.bin

