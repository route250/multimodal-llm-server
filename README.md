# multimodal-llm-server
マルチモーダルなAIとのチャットをするためのバックエンドサーバ

## 起動

```bash
./run-server.sh
```

既定では `0.0.0.0:13443` で HTTPS サーバ、`0.0.0.0:8088` で HTTP サーバを起動します。
自己署名証明書は `.local/tls/localhost.p12` に自動生成されます。

```bash
./run-server.sh 113443 0.0.0.0 13080
```

第1引数で HTTPS ポート、第2引数で待受アドレス、第3引数で HTTP ポートを指定できます。
