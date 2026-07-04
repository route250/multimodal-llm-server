#!/bin/bash

# 実行環境
PNAME=whisper-server
SCR_DIR="$(cd $(dirname $0);pwd)"
PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"
WHISPER_HOME="$PRJ_DIR/.local/opt/whisper.cpp"
WHISPER_BIN="$WHISPER_HOME/bin"
WHISPER_TMP="$WHISPER_HOME/tmp"
PKG_DIR="$SCR_DIR/pkg"

if [ ! -x $WHISPER_BIN/$PNAME ]; then
  if [ "$(uname -s)" == "Darwin" ]; then
    zip="$PKG_DIR/whisper-1.9.1-coreML-bin.zip"
  else
    zip="$PKG_DIR/whisper-1.9.1-xxxx-bin.zip"
  fi
  if [ ! -f "$zip" ]; then
     echo "ERROR: can not found $zip"
     exit 1
  fi
  mkdir -p "$WHISPER_TMP"
  echo "Extract $zip"
  (cd "$WHISPER_TMP"; unzip -q $zip )
  f=$(find $WHISPER_TMP -type f -name $PNAME )
  if [ ! -e "$f" ]; then
     echo "ERROR: can not extract $PNAME in $zip"
     exit 1
  fi
  dir=$(dirname $f)
  mkdir -p $WHISPER_BIN
  cp -pr $dir/* $WHISPER_BIN/
  if [ "$(uname -s)" == "Darwin" ]; then
    xattr -c $WHISPER_BIN/*
  fi
  rm -rf "$dir"
fi

if [ ! -x $WHISPER_BIN/$PNAME ]; then
  echo "ERROR: can not found $PNAME in $zip"
  exit 1
fi
exit 0
