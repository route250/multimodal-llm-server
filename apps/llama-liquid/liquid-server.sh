#!/bin/bash

# 実行環境
SCR_DIR="$(cd $(dirname $0);pwd)"
PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"

LIQUID_HOME="$PRJ_DIR/.local/opt/llama-liquid"
LIQUID_BIN="$LIQUID_HOME/bin"
LIQUID_LOG="$LIQUID_HOME/logs"

LLAMACPP_HOME="$PRJ_DIR/.local/opt/llama-cpp"
LLAMACPP_BIN="$LLAMACPP_HOME/bin"

# インストール
$SCR_DIR/liquid-install.sh

export PATH="$LLAMACPP_BIN:$LIQUID_BIN:$PATH"

# モデルディレクトリ
export CKPT="$LIQUID_HOME/models"

# モデルファイル
QT="Q4_0"
M0="LFM2.5-1.2B-JP-202606-${QT}.gguf"
M1="LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M2="mmproj-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M3="vocoder-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M4="tokenizer-LFM2.5-Audio-1.5B-JP-${QT}.gguf"

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
  wait 2>/dev/null || true
}
trap fn_cleanup EXIT

$LIQUID_BIN/llama-liquid-audio-server -lv 1 --port 8766 -m $CKPT/$M1 -mm $CKPT/$M2 -mv $CKPT/$M3 --tts-speaker-file $CKPT/$M4 >>"$LIQUID_LOGFILE" 2>&1 &
LIQUID_PID=$!

$LLAMACPP_BIN/llama-server -lv 1 --port 8767 -m $CKPT/$M0 >>"$LLAMACPP_LOGFILE" 2>&1 &
LLAMACPP_PID=$!

wait "$LIQUID_PID" "$LLAMACPP_PID"

