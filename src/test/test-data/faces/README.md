# Georgia Tech Face Database テストデータ

このディレクトリには、顔照合テストで使用する生成済み特徴ベクトルと、その再生成用HTMLを保存しています。
元画像はリポジトリに含めません。

## 保存しているファイル

- `README.md`: 出典、対象画像、再生成手順
- `generate-descriptors.html`: ブラウザで顔検出と128次元特徴ベクトル生成を行うHTML
- `descriptors.json`: 10人×10枚、合計100枚から生成済みの特徴ベクトル
- `images/`: `descriptors.json`の`filePath`に対応する320×120ピクセルのダミーJPEG画像

## 元データの取得と展開

リポジトリのルートから次のコマンドを実行します。

```bash
cd src/test/test-data/faces
curl -L --fail https://www.anefian.com/research/gt_db.zip -o gt_db.zip
shasum -a 256 gt_db.zip
unzip gt_db.zip
```

確認済みアーカイブの情報は次のとおりです。

```text
ファイルサイズ: 133192489 bytes
SHA-256: 2c4e379ef7c3cc5580eb409673a1e6eb75c5e5a7efd9bf3beb1de8059e835cbf
```

展開後は、HTMLと同じディレクトリに`gt_db/s01/01.jpg`などが存在する構成になります。

## descriptors.jsonの生成

別のターミナルでリポジトリのルートへ移動し、ローカルHTTPサーバを起動します。

```bash
python3 -m http.server 18080 --bind 127.0.0.1
```

次のURLを、WebGLを利用できるブラウザで開きます。

```text
http://127.0.0.1:18080/src/test/test-data/faces/generate-descriptors.html
```

HTMLはページ読み込み後に自動で処理を開始します。
画面に`完了: 成功 100枚 / 失敗 0枚`と表示されたことを確認し、`descriptors.jsonを保存`を押します。
ダウンロードされたファイルで、次のファイルを置き換えます。

```text
src/test/test-data/faces/descriptors.json
```

`descriptors.json`の`filePath`は、元の顔画像ではなく`images/person_NNN/image_NN.jpg`形式のダミー画像を参照します。
特徴ベクトルは`sourceImageId`に記録されたGeorgia Tech Face Databaseの画像から計算されています。
`images/`のダミー画像を特徴ベクトル生成の入力には使用しません。
各ダミー画像はImageMagickで生成した白背景・黒文字のJPEGで、中央に`personId`とファイル名を表示します。

生成後は、ダウンロードした元画像とZIPを削除できます。

```bash
rm -rf src/test/test-data/faces/gt_db
rm src/test/test-data/faces/gt_db.zip
```

## 対象画像

Georgia Tech Face Databaseの50人から、次の10人を固定で選択しています。
各人について10枚を使い、先頭7枚を登録用、末尾3枚を照合テスト用としています。

| テストID | 元データID | 使用画像 |
|---|---|---|
| `person_001` | `s01` | 01, 03, 04, 06, 07, 09, 10, 12, 13, 15 |
| `person_002` | `s03` | 01, 03, 04, 06, 07, 09, 10, 12, 13, 15 |
| `person_003` | `s08` | 01, 03, 04, 06, 07, 09, 10, 12, 13, 15 |
| `person_004` | `s09` | 01, 02, 05, 06, 08, 09, 11, 12, 14, 15 |
| `person_005` | `s05` | 01, 02, 03, 06, 07, 09, 10, 13, 14, 15 |
| `person_006` | `s15` | 02, 04, 05, 08, 09, 10, 11, 13, 14, 15 |
| `person_007` | `s18` | 01, 02, 03, 06, 07, 08, 09, 11, 12, 14 |
| `person_008` | `s02` | 01, 03, 04, 05, 06, 09, 10, 12, 14, 15 |
| `person_009` | `s06` | 02, 03, 04, 06, 07, 09, 10, 13, 14, 15 |
| `person_010` | `s11` | 01, 02, 03, 04, 05, 09, 10, 11, 14, 15 |

この選択は`generate-descriptors.html`内に固定で定義されているため、CSVファイルは不要です。

## 特徴ベクトル生成方式

`generate-descriptors.html`は`bot.html`と同じ方式で処理します。

- `@vladmandic/face-api` 1.7.15
- WebGL backend
- TinyFaceDetector: `inputSize=416`、`scoreThreshold=0.5`
- 最初の検出結果から面積が最大の顔を選択
- 顔の横幅の30%を上下左右の余白として切り出し
- 切り出した画像でもう一度顔検出
- FaceLandmark68NetとFaceRecognitionNetを使用
- 出力は128次元のdescriptor

生成結果はブラウザ、GPU、WebGL実装、ライブラリの取得時点によって小数値が変化する可能性があります。
照合テストでは、同一ファイルから一括生成したdescriptor同士を比較してください。

## 出典・利用上の注意（日本語）

出典はGeorgia Institute of Technologyで収集されたGeorgia Tech Face Databaseです。
公式ページでは、50人について1人あたり15枚、合計750枚のカラー画像が提供されています。
データセットは研究者向けに提供されており、標準的なオープンソースライセンスは明記されていません。
再配布、公開、商用利用を行う場合は、公式の説明と配布条件を確認してください。

- 公式ページ: https://www.anefian.com/research/face_reco.htm
- 公式ZIP: https://www.anefian.com/research/gt_db.zip
- 公式README: https://www.anefian.com/research/GTDB_README.txt

## Source and usage notice (English)

The source is the Georgia Tech Face Database collected at the Georgia Institute of Technology.
The official page describes 750 color images of 50 people, with 15 images per person.
The dataset is provided for researchers, and no standard open-source license is stated.
Review the official description and distribution terms before redistribution, publication, or commercial use.

- Official page: https://www.anefian.com/research/face_reco.htm
- Official ZIP: https://www.anefian.com/research/gt_db.zip
- Official README: https://www.anefian.com/research/GTDB_README.txt
