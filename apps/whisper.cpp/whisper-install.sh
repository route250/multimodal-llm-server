#!/bin/bash

# 実行環境
PNAME=whisper-server
SCR_DIR="$(cd $(dirname $0);pwd)"
PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"
WHISPER_HOME="$PRJ_DIR/.local/opt/whisper.cpp"
WHISPER_BIN="$WHISPER_HOME/bin"
WHISPER_TOOLS="$WHISPER_HOME/tools"
WHISPER_MODELS="$WHISPER_HOME/models"
WHISPER_TMP="$WHISPER_HOME/tmp"
PKG_DIR="$SCR_DIR/pkg"

if [ ! -x $WHISPER_BIN/$PNAME ]; then
  if [ "$(uname -s)" == "Darwin" ]; then
    TGZ="$PKG_DIR/whisper-1.9.1-bin-coreML.tar.gz"
  else
    TGZ="$PKG_DIR/whisper-1.9.1-bin-ubuntu-x64-cuda.tar.gz"
  fi
  if [ ! -f "$TGZ" ]; then
     echo "ERROR: can not found $TGZ"
     exit 1
  fi
  mkdir -p "$WHISPER_TMP"
  echo "Extract $TGZ"
  (cd "$WHISPER_TMP"; tar -zxf $TGZ )
  f=$(find $WHISPER_TMP -type f -name $PNAME )
  if [ ! -e "$f" ]; then
     echo "ERROR: can not extract $PNAME in $TGZ"
     exit 1
  fi
  dir=$(dirname $f)
  mkdir -p $WHISPER_BIN
  cp -pr $dir/* $WHISPER_BIN/
  if [ "$(uname -s)" == "Darwin" ]; then
    xattr -c $WHISPER_BIN/*
  fi
  rm -rf "$dir"
  #
  TGZ="$PKG_DIR/whisper-1.9.1-models.tar.gz"
  echo "Extract $TGZ"
  rm -rf "$WHISPER_TMP/models"
  (cd "$WHISPER_TMP"; tar -zxf $TGZ )
  if [ ! -d "$WHISPER_TMP/models" ]; then
     echo "ERROR: can not extract models in $TGZ"
     exit 1
  fi
  rm -rf "$WHISPER_TOOLS"
  mv "$WHISPER_TMP/models" "$WHISPER_TOOLS"
fi

if [ ! -x $WHISPER_BIN/$PNAME ]; then
  echo "ERROR: can not found $PNAME in $TGZ"
  exit 1
fi

# download models
MODEL=small
mkdir -p "$WHISPER_MODELS"
if [ ! -e "$WHISPER_MODELS/ggml-${MODEL}.bin" ]; then
  echo "Download model $MODEL"
  "${WHISPER_TOOLS}/download-ggml-model.sh" $MODEL "$WHISPER_MODELS"
fi

if [ "$(uname -s)" == "Darwin" ]; then
  if [ ! -f "$WHISPER_MODELS/ggml-small-encoder.mlmodelc/weights/weight.bin" ]; then
    VENV="$WHISPER_TMP/venv"
    (
      function fn_cleanup() {
        echo "exit from venv"
        deactivate >/dev/null 2>&1 || true
        rm -f "$WHISPER_MODELS/convert-whisper-to-coreml.py"
        rm -rf "$WHISPER_MODELS/coreml-encoder-small.mlpackage"
        rm -rf $VENV
      }
      trap fn_cleanup EXIT
      cd "$WHISPER_MODELS"
      rm -rf $VENV || exit 9
      echo "Create python venv"
      python3 -m venv $VENV || exit 9
      source $VENV/bin/activate
      $VENV/bin/pip install --quiet -U pip setuptools || exit 9
      $VENV/bin/pip install --quiet -r "$WHISPER_TOOLS/requirements-coreml.txt" || exit 9
      echo "Convert to coreml model"
      cp "$WHISPER_TOOLS/convert-whisper-to-coreml.py" "$WHISPER_MODELS" || exit 9
      "$WHISPER_TOOLS/generate-coreml-model.sh" $MODEL || exit 9
    ) || exit 9
  fi
fi

exit 0
