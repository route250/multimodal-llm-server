

## LiquidAIのデータについて

### LiquidAIのrunnerは以下のURLから入手した。

将来的にllama.cppにマージされるらしいが、いつになるかわからない。
https://github.com/ggml-org/llama.cpp/pull/18641

https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-JP-GGUF/resolve/main/runners/llama-liquid-audio-macos-arm64.zip?download=true

https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-JP-GGUF/resolve/main/runners/llama-liquid-audio-ubuntu-x64.zip?download=true

### LiquidAIのモデル

LiquidAI/LFM2.5-1.2B-JP-202606-GGUF

LiquidAI/LFM2.5-Audio-1.5B-JP-GGUF


export CKPT=/path/to/LFM2.5-Audio-1.5B-JP-GGUF
export INPUT_WAV=/path/to/input.wav
export OUTPUT_WAV=/path/to/output.wav

export CKPT=/path/to/LFM2.5-Audio-1.5B-JP-GGUF
./llama-liquid-audio-server -m $CKPT/LFM2.5-Audio-1.5B-Q4_0.gguf -mm $CKPT/mmproj-LFM2.5-Audio-1.5B-Q4_0.gguf -mv $CKPT/vocoder-LFM2.5-Audio-1.5B-Q4_0.gguf --tts-speaker-file $CKPT/tokenizer-LFM2.5-Audio-1.5B-Q4_0.gguf
