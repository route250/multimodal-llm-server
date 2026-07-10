#!/bin/bash

# 実行環境
PNAME=whisper-server
SCR_DIR="$(cd $(dirname $0);pwd)"
PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"
WHISPER_HOME="$PRJ_DIR/.local/opt/whisper.cpp"
WHISPER_BIN="$WHISPER_HOME/bin"
WHISPER_MODELS="$WHISPER_HOME/models"
PKG_DIR="$SCR_DIR/pkg"

# whisper-serverのインストール
$SCR_DIR/whisper-install.sh

export PATH="$WHISPER_BIN:$PATH"

whisper-server --port 8768 -m $WHISPER_MODELS/ggml-small.bin

