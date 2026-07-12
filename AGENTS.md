# AGENTS.md

## プロジェクト概要

このリポジトリは、HTTP サーバとして音声とテキストを受け取り、LLM とチャットする機能を提供するバックエンドサーバです。

現在の実装は Java 21 を前提にし、JDK 標準の `com.sun.net.httpserver.HttpServer` を使用します。HTTP リクエスト処理は virtual thread executor で実行します。

## 主要仕様

詳細仕様は `docs/server-specs.md` を参照してください。

主な責務:

- `src/main/resources/html` 配下の静的ファイル配信
- `/chat/connect` による SSE 接続
- `/chat/request` によるテキスト・音声チャンク受信
- `ChatGroup` をチャットルームとして扱う通信
- `ChatClient` をルーム内の接続クライアントとして扱う通信

## 実行

コンパイル:

```bash
mvn -q -DskipTests compile
```

起動:

```bash
./run-server.sh
```

ポート指定:

```bash
./run-server.sh 18080
```

## 実装方針

- まず JDK 標準機能で実装する。
- 不要な依存ライブラリを追加しない。
- HTTP ルーティングとチャットルームの責務を混ぜない。
- `MlServer` は HTTP プロトコルとルーティングを担当する。
- `ChatGroup` はチャットルームを担当する。
- `ChatClient` は接続中クライアントを担当する。
- `ChatRequest` は入力内容の分類とイベント変換を担当する。
- `ServerEvent` は SSE で配信するイベントを表す。

## コーディングガイドライン

- クラス、メソッド、変数には適切に日本語でコメントを残す。
- 軽微なスペルミスは適切に修正する。
- 実装はできるだけシンプルなコードにする。
- コードの修正・変更は、全体的な最適化も含めて検討すること。局所的な修正で全体が複雑になるくらいならリファクタリングした方がいい。
- 指定がない限り、過去互換は考えない。古いコードは整理してシンプルにする。

## 静的ファイル

チャット API 以外の URL は `src/main/resources/html` 配下で解決します。

```text
/        -> src/main/resources/html/index.html
/foo     -> src/main/resources/html/foo または src/main/resources/html/foo/index.html
/foo/    -> src/main/resources/html/foo/index.html
/foo.js  -> src/main/resources/html/foo.js
```

HTML、CSS、JavaScript の変更だけで済む場合は Java コードを変更しないでください。

## チャット通信

基本フロー:

```text
GET  /chat/connect?group=group-1&sessionId=user-a
POST /chat/request?group=group-1&sessionId=user-a
```

`/chat/request` は `sessionId` から接続済み `ChatClient` を取得し、その `ChatClient` にリクエストを渡します。

```text
MlServer#handleChatRequest
  -> ChatGroup#client(sessionId)
  -> ChatClient#handle(ChatRequest)
  -> ChatClient が ChatGroup へ ServerEvent を送る
  -> ChatGroup が接続中 ChatClient 全員へ配信
  -> /chat/connect の SSE からクライアントへ届く
```

## デフォルト ChatGroup

サーバ起動時に以下の `ChatGroup` を作成します。

```text
group-1
group-2
group-3
```

`group` が省略された場合は `group-1` を使用します。

## 注意事項

- 認証は未実装です。
- LLM 連携は実装済みです。
- 音声チャンクは PCM16LE とブラウザ VAD byte を受信し、VAD/STT/LLM/TTS の非同期処理に渡します。
- チャット履歴は `ChatClient` ごとに直近 20 件の user/assistant メッセージをメモリ上で保持します。
- ユーザー発話は LLM 呼び出し前には履歴へ確定しません。再生確認済み assistant chunk が届いた時点で、対応するユーザー発話と assistant 応答を履歴へ確定します。
- LLM 応答失敗、TTS 失敗、クライアント未再生、再生報告欠落では、assistant 応答を履歴へ追加せず、ユーザー発話だけを履歴へ残します。
- assistant 応答は TTS 分割後の chunk 単位ではなく、同じ `assistantTurnId` の再生確認済み chunk テキストを結合した 1 件の assistant メッセージとして履歴へ保持します。
- `sessionId` の衝突時の仕様は未確定です。
