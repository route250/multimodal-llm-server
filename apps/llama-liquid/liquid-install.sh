#!/bin/bash

# 実行環境
SCR_DIR="$(cd $(dirname $0);pwd)"
PKG_DIR="$SCR_DIR/pkg"

PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"

LIQUID_CMD=llama-liquid-audio-server
LIQUID_HOME="$PRJ_DIR/.local/opt/llama-liquid"
LIQUID_BIN="$LIQUID_HOME/bin"
LIQUID_TMP="$LIQUID_HOME/tmp"
LIQUID_LOG="$LIQUID_HOME/logs"

LLAMACPP_CMD="llama-server"
LLAMACPP_HOME="$PRJ_DIR/.local/opt/llama-cpp"
LLAMACPP_BIN="$LLAMACPP_HOME/bin"

if [ "$(uname -s)" == "Darwin" ]; then
  LIQUID_PKG="$PKG_DIR/llama-liquid-audio-macos-arm64.zip"
  LLAMACPP_PKG="$PKG_DIR/llama-b9946-bin-macos-arm64.tar.gz"
else
  LIQUID_PKG="$PKG_DIR/llama-liquid-audio-ubuntu-x64.zip"
  LLAMACPP_PKG="$PKG_DIR/llama-b9946-bin-ubuntu-x64-cuda.tar.gz"
fi

function extract() {
  local BIN=$1
  local CMD=$2
  local PKG=$3
  local f
  local dir
  if [ ! -x $BIN/$CMD ]; then
    echo "Extract $PKG"
    mkdir -p "$LIQUID_TMP"
    if [[ "$PKG" =~ \.zip$ ]]; then
      (cd "$LIQUID_TMP"; unzip -q $PKG )
    elif [[ "$PKG" =~ \.tar\.gz$ ]]; then
      (cd "$LIQUID_TMP"; tar -zxf $PKG )
    fi
    f=$(find $LIQUID_TMP -type f -name $CMD )
    if [ ! -e "$f" ]; then
       echo "ERROR: can not found $CMD in $PKG"
       exit 1
    fi
    dir=$(dirname $f)
    mkdir -p $BIN
    cp -pr $dir/* $BIN/
    if [ "$(uname -s)" == "Darwin" ]; then
      xattr -c $BIN/*
    fi
    rm -rf "$dir"
  fi
}

extract $LIQUID_BIN $LIQUID_CMD $LIQUID_PKG
extract $LLAMACPP_BIN $LLAMACPP_CMD $LLAMACPP_PKG

if [ ! -x $LIQUID_BIN/$LIQUID_CMD ]; then
  echo "ERROR: can not found $LIQUID_CMD in $PKG"
  exit 1
fi
if [ ! -x $LLAMACPP_BIN/$LLAMACPP_CMD ]; then
  echo "ERROR: can not found $LLAMACPP_CMD in $PKG"
  exit 1
fi
mkdir -p $LIQUID_LOG
exit 0
