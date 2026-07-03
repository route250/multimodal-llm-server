#!/bin/bash

# 実行環境
PRJ_DIR="$(cd $(dirname $0);pwd)"
LIQUID_BIN="$PRJ_DIR/llama-liquid-macos-b8256"
export PATH="$LIQUID_BIN:$PATH"

# モデルディレクトリ
export CKPT="$PRJ_DIR/models"

# モデルファイル
QT="Q4_0"
M1="LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M2="mmproj-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M3="vocoder-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M4="tokenizer-LFM2.5-Audio-1.5B-JP-${QT}.gguf"

# モデル確認&ダウンロード
$PRJ_DIR/llama-liquid-audio-model.sh $QT

# サーバ実行
$LIQUID_BIN/llama-liquid-audio-server -m $CKPT/$M1 -mm $CKPT/$M2 -mv $CKPT/$M3 --tts-speaker-file $CKPT/$M4 $*

