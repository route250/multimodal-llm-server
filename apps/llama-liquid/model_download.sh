#!/bin/bash

# 実行環境
SCR_DIR="$(cd $(dirname $0);pwd)"
PRJ_DIR="$(cd $SCR_DIR/../..;pwd)"
LIQUID_HOME="$PRJ_DIR/.local/opt/llama-liquid"

# モデルディレクトリ
export CKPT="$LIQUID_HOME/models"
mkdir -p "$CKPT"

# モデルリポジトリ
HF_URL=https://huggingface.co
REPO0=LiquidAI/LFM2.5-1.2B-JP-202606-GGUF
REPO1=LiquidAI/LFM2.5-Audio-1.5B-JP-GGUF

# モデルファイル
QT=${1:-Q4_0}
M0="$REPO0,LFM2.5-1.2B-JP-202606-${QT}.gguf"
M1="$REPO1,LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M2="$REPO1,mmproj-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M3="$REPO1,vocoder-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M4="$REPO1,tokenizer-LFM2.5-Audio-1.5B-JP-${QT}.gguf"

GGF_LIST="$M0 $M1 $M2 $M3 $M4"

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
for gguf in $GGF_LIST; do
    repo="${gguf%%,*}"
    gguf="${gguf#*,}"
    target="$CKPT/$gguf"
    if [ ! -f "$target" ] || [ "$(stat $STAT_OPT "$target")" -le $((10 * 1024 * 1024)) ]; then
        echo "download $gguf..."
        url="https://huggingface.co/$repo/resolve/main/$gguf"
        curl -sS  -L $url'?download=true' -o "$CKPT/$gguf"
    fi
done

