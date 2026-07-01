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
- LLM 連携は未実装です。
- 音声チャンクの受信はありますが、音声認識やデコードは未実装です。
- チャット履歴は保持していません。
- `sessionId` の衝突時の仕様は未確定です。
