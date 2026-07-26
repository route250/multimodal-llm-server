# multimodal-llm-server

LFM2.5-Audio を評価するために作った、Java 21 製の音声チャットボットです。
ブラウザからテキストとマイク音声を受け取り、LFM2.5-Audio による音声認識（ASR）・音声合成（TTS）、llama.cpp による LLM 応答を組み合わせて会話します。静的フロントエンドの配信、Server-Sent Events（SSE）によるイベント配信、VAD、顔検出結果に応じた会話を 1 プロセスで扱います。

ASR と TTS には llama-liquid-server（実行ファイル名: `llama-liquid-audio-server`）を、LLM には llama.cpp の `llama-server` を利用します。どちらも OpenAI 互換 API として接続します。

## 主な機能

- Java 標準の `HttpServer` / `HttpsServer` と virtual thread による HTTP サーバ
- HTTPS と HTTP の同時待受。LAN などループバック以外からの HTTP は HTTPS へ `308 Permanent Redirect` で転送
- SSE と HTTP POST によるリアルタイムチャット
- テキスト入力、PCM16LE マイク音声、ブラウザ側 VAD/RMS 情報の処理
- `llama-liquid-audio-server` を利用する LFM2.5-Audio の ASR/TTS
- llama.cpp の `llama-server` を利用する LLM 応答
- 3 個の既定チャットルーム（`group-1`、`group-2`、`group-3`）とグループ別 LLM 設定
- `src/main/resources/html` 配下の静的フロントエンド配信

## 使用ライブラリ・モデル

このプロジェクトは次のサードパーティ製ライブラリ、実行基盤、モデルを使用しています。

| 名称 | 用途 | 出典 |
| --- | --- | --- |
| [LFM2.5-Audio-1.5B-JP](https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-JP-GGUF) | 日本語 ASR/TTS 用モデル | [Liquid AI](https://www.liquid.ai/models) |
| [LFM2.5-1.2B-JP](https://huggingface.co/LiquidAI/LFM2.5-1.2B-JP-202606-GGUF) | 既定の日本語 LLM モデル | [Liquid AI](https://www.liquid.ai/models) |
| [Gemma 4 E2B](https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF) | `group-2` の既定 LLM モデル。GGUF 配布物を使用 | [Google Gemma](https://ai.google.dev/gemma) |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | `llama-server` による GGUF モデルの LLM 推論 | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) |
| [OpenAI Java SDK](https://github.com/openai/openai-java) | OpenAI Responses API 互換の LLM クライアント | [openai/openai-java](https://github.com/openai/openai-java) |
| [TEN VAD](https://github.com/TEN-framework/ten-vad) | ブラウザ内の音声区間検出 | [TEN-framework/ten-vad](https://github.com/TEN-framework/ten-vad) |
| [Smart Turn v3](https://huggingface.co/pipecat-ai/smart-turn-v3) | サーバ側の発話ターン完了判定。ONNX モデルを初回実行時に取得 | [pipecat-ai/smart-turn-v3](https://huggingface.co/pipecat-ai/smart-turn-v3) |
| [ONNX Runtime](https://onnxruntime.ai/) | Smart Turn v3 ONNX モデルの Java 推論実行 | [onnxruntime](https://github.com/microsoft/onnxruntime) |

各コンポーネントのライセンスと利用条件は、リンク先および同梱ファイルの `LICENSE` を確認してください。

## 必要環境

- macOS（Apple Silicon）または Ubuntu（x86_64、CUDA 対応バイナリ）
- JDK 21
- Apache Maven 3.9 以降
- `bash`、`curl`、`unzip`、`tar`

初回起動時には Smart Turn v3 の ONNX モデルをダウンロードします。サーバ起動時には ASR、TTS、LLM の疎通確認を実行するため、先にモデルサービスを起動してください。

同梱の `apps/llama-liquid` には、macOS（Apple Silicon）用と Ubuntu（x86_64、CUDA）用の実行ファイルと補助スクリプトを配置しています。

## Quick Start

### 1. リポジトリを取得して依存関係を確認する

```bash
git clone https://github.com/route250/multimodal-llm-server.git
cd multimodal-llm-server
mvn -q -DskipTests compile
```

### 2. モデルサービスを起動する

別のターミナルで、次のスクリプトを実行します。

```bash
./apps/llama-liquid/liquid-server.sh
```

このスクリプトは、初回実行時に以下を行います。

| 処理 | 内容 | 保存先 |
| --- | --- | --- |
| 実行ファイルの展開 | `llama-liquid-audio-server` と llama.cpp の `llama-server` を展開 | `.local/opt/llama-liquid/bin`、`.local/opt/llama-cpp/bin` |
| モデルのダウンロード | LFM2.5、Gemma 4、LFM2.5-Audio の GGUF ファイルを取得 | `.local/opt/llama-liquid/models` |
| 音声モデルサービス | llama-liquid-server（`llama-liquid-audio-server`）をポート `8766` で起動 | `http://localhost:8766/v1/chat/completions` |
| LLM サービス | llama.cpp の `llama-server` をポート `8767` で起動 | `http://localhost:8767/v1` |

スクリプトは 2 つのモデルサービスを起動したまま待機します。このターミナルは終了せず、次の手順は別のターミナルで実行してください。

モデルサービスのログは `.local/opt/llama-liquid/logs` に出力されます。LiquidAI モデルに関する補足は [apps/llama-liquid/README.md](apps/llama-liquid/README.md) を参照してください。

OpenAI API を `group-3` で使用する場合は、起動前に API キーを設定します。

```bash
export OPENAI_API_KEY='sk-...'
```

### 3. サーバを起動する

```bash
./run-server.sh
```

起動後、ブラウザで次を開きます。

```text
https://localhost:13443/
```

ローカル開発用の自己署名証明書を初回に `.local/tls/localhost.p12` へ生成します。ブラウザの証明書警告では、ローカル開発用証明書であることを確認してからアクセスを続行してください。

## 接続先とポート

| プロトコル | 既定の待受 | 挙動 |
| --- | --- | --- |
| HTTPS | `0.0.0.0:13443` | 静的ファイルとチャット API を提供 |
| HTTP | `0.0.0.0:13080` | `127.0.0.1` と `::1` は通常処理、それ以外は HTTPS へ転送 |

HTTPS ポート、待受アドレス、HTTP ポートはこの順に指定できます。

```bash
./run-server.sh 14443 127.0.0.1 18080
```

## 設定

既定では llama.cpp の `llama-server`（`http://127.0.0.1:8767/v1`）を LLM として使用します。
各グループの設定画面または `POST /chat/settings` では、実際の会話で使うベース URL、モデル、API キー、ボット名、システムプロンプトを更新できます。設定は `.local/<group>/llm.json` に保存されます。

## プロジェクト構成

```text
src/main/java/               サーバ、LLM、音声、顔認識の実装
src/main/resources/html/     ブラウザ向け静的ファイル
src/test/                    JUnit テストとテストデータ
docs/server-specs.md         HTTP/SSE と音声処理の仕様
apps/llama-liquid/           LFM2.5-Audio と llama.cpp の起動・モデル取得スクリプト
run-server.sh                コンパイルしてサーバを起動するスクリプト
```

## 開発

コンパイルとテストは Maven で実行します。

```bash
mvn -q -DskipTests compile
mvn test
```

HTML、CSS、JavaScript だけを変更する場合は `src/main/resources/html` を編集してください。サーバはファイルシステム上の静的ファイルを優先して配信するため、Java コードの変更は不要です。

## セキュリティと制約

- 認証・認可は未実装です。信頼できるネットワークだけで使用してください。
- `sessionId` の衝突時は、後から接続したクライアントが同じグループ内の接続を置き換えます。
- 会話履歴はクライアントごとにメモリ上で最大 20 件保持し、切断時に失われます。
- `.local/` には TLS 証明書、グループ設定、実行ファイル、ダウンロード済みモデル、ログが保存されます。API キーを含む可能性があるため、公開リポジトリへ追加しないでください。

## 詳細仕様

エンドポイント、SSE イベント、音声入力形式、会話履歴の確定条件は [docs/server-specs.md](docs/server-specs.md) に記載しています。
