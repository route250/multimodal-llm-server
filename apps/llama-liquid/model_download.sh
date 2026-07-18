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
REPO01=LiquidAI/LFM2.5-1.2B-JP-202606-GGUF
REPO02=unsloth/gemma-4-E2B-it-GGUF
REPO10=LiquidAI/LFM2.5-Audio-1.5B-JP-GGUF

# モデルファイル
QT=${1:-Q4_0}
M01="$REPO01,LFM2.5-1.2B-JP-202606-${QT}.gguf"
M02="$REPO02,gemma-4-E2B-it-${QT}.gguf"
M11="$REPO10,LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M12="$REPO10,mmproj-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M13="$REPO10,vocoder-LFM2.5-Audio-1.5B-JP-${QT}.gguf"
M14="$REPO10,tokenizer-LFM2.5-Audio-1.5B-JP-${QT}.gguf"

GGF_LIST="$M01 $M02 $M11 $M12 $M13 $M14"

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
        curl -sS  -L "$url?download=true" -o "$CKPT/$gguf"
    fi
done

