# 顔認証デバッグ用データ / Face Recognition Debug Dataset

## 日本語

### データ内容

このディレクトリには、AT&T/ORL Database of Faces から抽出した顔認証デバッグ用データを格納しています。

- 人物数: 10人
- 画像数: 1人10枚、合計100枚
- 画像形式: JPEG、8-bitグレースケール
- 画像サイズ: 92×112ピクセル
- 登録・学習用: 各人7枚、合計70枚（`split=train`）
- 照合テスト用: 各人3枚、合計30枚（`split=test`）

人物は、固定乱数シード `20260718` を使って原本の40人から選択しました。
選択した原本の人物IDは `s4, s5, s11, s17, s21, s23, s24, s25, s30, s39` です。
このリポジトリでは、人物IDを `person_001`〜`person_010` に置き換えています。

### ファイル構成

- `images/person_NNN/`: 人物IDごとの顔画像
- `persons.csv`: 人物マスター用データ
- `face_images.csv`: 顔画像マスター用データ、分割区分、原本ID、SHA-256
- `descriptors.json`: 100枚分の事前計算済み128次元特徴ベクトル
- `generate-descriptors.html`: 特徴ベクトル再生成ページ
- `SOURCE_README.txt`: 原本アーカイブに含まれる説明

### 特徴ベクトル

`descriptors.json` は `@vladmandic/face-api` バージョン `1.7.15` の
`FaceRecognitionNet` で生成しています。画像はすでに顔領域へ切り出されているため、
顔検出とランドマーク検出は実行せず、92×112ピクセルの画像全体を顔画像として入力しています。

再生成する場合は、リポジトリのルートディレクトリで次のHTTPサーバを起動します。

```bash
python3 -m http.server 18080 --bind 127.0.0.1
```

ブラウザで次のURLを開き、処理完了後に `descriptors.jsonを保存` を押します。

```text
http://127.0.0.1:18080/src/test/test-data/faces/generate-descriptors.html
```

生成処理はモデル取得にCDNを使用するため、インターネット接続が必要です。

### 出典

データセット:

> AT&T Laboratories Cambridge, The Database of Faces  
> 旧名称: The ORL Database of Faces  
> https://www.cl.cam.ac.uk/research/dtg/attarchive/facedatabase.html

参考文献:

> F. Samaria and A. Harter, “Parameterisation of a Stochastic Model for Human Face Identification,”  
> 2nd IEEE Workshop on Applications of Computer Vision, Sarasota, Florida, December 1994.

原本READMEの指定に従い、この画像を使用する場合は Olivetti Research Laboratory または
AT&T Laboratories Cambridge のクレジットを記載してください。

### 利用上の注意

このデータには実在人物の生体情報が含まれます。ローカルのデバッグおよびテスト用途に限定し、
アプリケーションの公開データ、ログ、画面キャプチャ、外部ストレージへ複製しないでください。
原本ページには一般的なオープンソースライセンスの記載がないため、商用利用、再配布、
学習済みモデルの公開を行う場合は、権利者へ利用条件を確認してください。

## English

### Dataset contents

This directory contains face-recognition debug data extracted from the AT&T/ORL Database of Faces.

- Subjects: 10
- Images: 10 per subject, 100 total
- Format: JPEG, 8-bit grayscale
- Dimensions: 92×112 pixels
- Enrollment/training split: 7 per subject, 70 total (`split=train`)
- Matching test split: 3 per subject, 30 total (`split=test`)

The subjects were selected from the 40 original subjects using the fixed random seed `20260718`.
The selected source subject IDs are `s4, s5, s11, s17, s21, s23, s24, s25, s30, s39`.
They are mapped to `person_001` through `person_010` in this repository.

### Files

- `images/person_NNN/`: Face images grouped by person ID
- `persons.csv`: Person master records
- `face_images.csv`: Face-image records, split labels, source IDs, and SHA-256 values
- `descriptors.json`: Precomputed 128-dimensional descriptors for all 100 images
- `generate-descriptors.html`: Descriptor regeneration page
- `SOURCE_README.txt`: README included in the original archive

### Face descriptors

`descriptors.json` was generated with `FaceRecognitionNet` from
`@vladmandic/face-api` version `1.7.15`. Since every source image is already cropped to a face,
the generator skips face and landmark detection and treats the complete 92×112 image as its face input.

To regenerate the file, start an HTTP server from the repository root:

```bash
python3 -m http.server 18080 --bind 127.0.0.1
```

Open the following URL and select `descriptors.jsonを保存` after processing completes:

```text
http://127.0.0.1:18080/src/test/test-data/faces/generate-descriptors.html
```

The generator requires an internet connection because it loads the model from a CDN.

### Source

Dataset:

> AT&T Laboratories Cambridge, The Database of Faces  
> Formerly: The ORL Database of Faces  
> https://www.cl.cam.ac.uk/research/dtg/attarchive/facedatabase.html

Reference:

> F. Samaria and A. Harter, “Parameterisation of a Stochastic Model for Human Face Identification,”  
> 2nd IEEE Workshop on Applications of Computer Vision, Sarasota, Florida, December 1994.

As requested by the original README, credit Olivetti Research Laboratory or
AT&T Laboratories Cambridge when using these images.

### Usage notice

This dataset contains biometric data of real people. Limit its use to local debugging and testing.
Do not copy it into public application data, logs, screenshots, or external storage.
The source page does not state a standard open-source license. Confirm usage terms with the rights holder
before commercial use, redistribution, or publication of a trained model.
