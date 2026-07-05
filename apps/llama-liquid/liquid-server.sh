#!/bin/bash

# 実行環境
SCR_DIR="$(cd $(dirname $0);pwd)"
PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"
LIQUID_HOME="$PRJ_DIR/.local/opt/llama-liquid"
LIQUID_BIN="$LIQUID_HOME/bin"

$SCR_DIR/liquid-install.sh

export PATH="$LIQUID_BIN:$PATH"

# モデルディレクトリ
export CKPT="$LIQUID_HOME/models"

# モデルファイル
QT="Q4_0"
M1="LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M2="mmproj-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M3="vocoder-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M4="tokenizer-LFM2.5-Audio-1.5B-JP-${QT}.gguf"

# モデル確認&ダウンロード
$SCR_DIR/model_download.sh $QT

# サーバ実行
$LIQUID_BIN/llama-liquid-audio-server --port 8766 -m $CKPT/$M1 -mm $CKPT/$M2 -mv $CKPT/$M3 --tts-speaker-file $CKPT/$M4 $*
