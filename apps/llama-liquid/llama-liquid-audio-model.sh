#!/bin/bash

# 実行環境
PRJ_DIR="$(cd $(dirname $0);pwd)"
LIQUID_BIN="$PRJ_DIR/llama-liquid-macos-b8256"
export PATH="$LIQUID_BIN:$PATH"

# モデルディレクトリ
export CKPT="$PRJ_DIR/models"
mkdir -p "$CKPT"

# モデルリポジトリ
HF_URL=https://huggingface.co
REPO=LiquidAI/LFM2.5-Audio-1.5B-JP-GGUF

# モデルファイル
QT=${1:-Q4_0}
M1="LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M2="mmproj-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M3="vocoder-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M4="tokenizer-LFM2.5-Audio-1.5B-JP-${QT}.gguf"

GGF_LIST="$M1 $M2 $M3 $M4"

# statオプション
if stat -c %s /dev/zero >/dev/null 2>&1; then
    echo "detect stat -c %s (linux)"
    STAT_OPT="-c %s"
elif stat -f %z /dev/zero >/dev/null 2>&1; then
    echo "detect stat -c %s (macos or bsd)"
    STAT_OPT="-f %z"
else
    echo "ERROR: can not detect stat option?"
    exit 1
fi

# モデル確認&ダウンロード
for ggf in $GGF_LIST; do
    target="$CKPT/$ggf"
    if [ ! -f "$target" ] || [ "$(stat $STAT_OPT "$target")" -le $((10 * 1024 * 1024)) ]; then
        echo "download $ggf..."
        url="https://huggingface.co/$REPO/resolve/main/$ggf"
        curl -sS -L $url -o "$CKPT/$ggf"
    fi
done

