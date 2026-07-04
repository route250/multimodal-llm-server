#!/bin/bash

# 実行環境
PNAME=llama-liquid-audio-server
SCR_DIR="$(cd $(dirname $0);pwd)"
PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"
LIQUID_HOME="$PRJ_DIR/.local/opt/llama-liquid"
LIQUID_BIN="$LIQUID_HOME/bin"
LIQUID_TMP="$LIQUID_HOME/tmp"
PKG_DIR="$SCR_DIR/pkg"

if [ ! -x $LIQUID_BIN/$PNAME ]; then
  if [ "$(uname -s)" == "Darwin" ]; then
    zip="$PKG_DIR/llama-liquid-audio-macos-arm64.zip"
  else
    zip="$PKG_DIR/llama-liquid-audio-ubuntu-x64.zip"
  fi
  mkdir -p "$LIQUID_TMP"
  echo "Extract $zip"
  (cd "$LIQUID_TMP"; unzip -q $zip )
  f=$(find $LIQUID_TMP -type f -name $PNAME )
  if [ ! -e "$f" ]; then
     echo "ERROR: can not found $PNAME in $zip"
     exit 1
  fi
  dir=$(dirname $f)
  mkdir -p $LIQUID_BIN
  cp -pr $dir/* $LIQUID_BIN/
  if [ "$(uname -s)" == "Darwin" ]; then
    xattr -c $LIQUID_BIN/*
  fi
  rm -rf "$dir"
fi

if [ ! -x $LIQUID_BIN/$PNAME ]; then
  echo "ERROR: can not found $PNAME in $zip"
  exit 1
fi
exit 0
