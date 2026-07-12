# multimodal-llm-server
マルチモーダルなAIとのチャットをするためのバックエンドサーバ

## 起動

```bash
./run-server.sh
```

既定では `0.0.0.0:8443` で HTTPS サーバを起動します。自己署名証明書は `.local/tls/localhost.p12` に自動生成されます。

```bash
./run-server.sh 18443 0.0.0.0
```

第1引数でポート、第2引数で待受アドレスを指定できます。
