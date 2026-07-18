#!/bin/bash

# 実行環境
SCR_DIR="$(cd $(dirname $0);pwd)"
PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"

LIQUID_HOME="$PRJ_DIR/.local/opt/llama-liquid"
LIQUID_BIN="$LIQUID_HOME/bin"
LIQUID_LOG="$LIQUID_HOME/logs"
LIQUID_PORT=8766

LLAMACPP_HOME="$PRJ_DIR/.local/opt/llama-cpp"
LLAMACPP_BIN="$LLAMACPP_HOME/bin"
LLAMACPP_PORT=8767

# インストール
$SCR_DIR/liquid-install.sh

export PATH="$LLAMACPP_BIN:$LIQUID_BIN:$PATH"

# モデルディレクトリ
export CKPT="$LIQUID_HOME/models"

# モデルファイル
QT="Q4_0"
#M0="LFM2.5-1.2B-JP-202606-${QT}.gguf"
M11="LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M12="mmproj-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M13="vocoder-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M14="tokenizer-LFM2.5-Audio-1.5B-JP-${QT}.gguf"

# モデル確認&ダウンロード
$SCR_DIR/model_download.sh $QT

# サーバ実行
mkdir -p $LIQUID_LOG
LIQUID_LOGFILE="$LIQUID_LOG/$(date +'liquid-server-%Y%m%d.log')"
LLAMACPP_LOGFILE="$LIQUID_LOG/$(date +'llama-cpp-%Y%m%d.log')"

LIQUID_PID=""
LLAMACPP_PID=""

function fn_cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$LIQUID_PID" ]] && kill -0 "$LIQUID_PID" 2>/dev/null; then
    kill "$LIQUID_PID"
  fi
  if [[ -n "$LLAMACPP_PID" ]] && kill -0 "$LLAMACPP_PID" 2>/dev/null; then
    kill "$LLAMACPP_PID"
  fi
  echo "wait for process..."
  wait 2>/dev/null || true
}
trap fn_cleanup EXIT

echo "start liquid-audio http://localhost:$LIQUID_PORT"
$LIQUID_BIN/llama-liquid-audio-server -lv 1 --port $LIQUID_PORT -m $CKPT/$M11 -mm $CKPT/$M12 -mv $CKPT/$M13 --tts-speaker-file $CKPT/$M14 >>"$LIQUID_LOGFILE" 2>&1 &
LIQUID_PID=$!

echo "start llama-server http://localhost:$LLAMACPP_PORT"
"$LLAMACPP_BIN/llama-server" -lv 1 --port "$LLAMACPP_PORT" --models-dir "$CKPT" --models-max 1 --models-autoload --sleep-idle-seconds 600 >>"$LLAMACPP_LOGFILE" 2>&1 &
LLAMACPP_PID=$!

wait "$LIQUID_PID" "$LLAMACPP_PID"

