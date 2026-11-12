# Web Audiveris

[English](#english) · [日本語](#日本語)

## English

Web Audiveris is a browser-based optical music recognition app that ports the [Audiveris](https://github.com/Audiveris/audiveris) recognition engine to WebAssembly. Select a sheet music image and download the recognized score as MusicXML 4.0.

**Live app: [https://1921music.github.io/web_audiveris/](https://1921music.github.io/web_audiveris/)**

The interface displays English as the main text, with smaller Japanese translations below.

### How to use

1. Open the live app in a browser that supports WebAssembly GC. Chromium is used for verification.
2. Choose a score image, or drag and drop it onto the page.
3. Click **Start recognition**. Progress is displayed during processing, and you can cancel the job.
4. Download the MusicXML when recognition finishes.

Recognition runs in your browser; score images are not uploaded to a server. The engine, fonts and OCR model are downloaded during initial loading.

PNG, JPEG, WebP and BMP are supported, one image at a time. Each image is limited to 64 MiB, 32 Mi pixels and 32,768 pixels per side. TIFF, PDF and assembling multiple images into a book are not supported.

### About the results

Automatic recognition can make mistakes. Review and correct the exported MusicXML in a score editor. This web app does not include an editor for recognition results.

If multiple pieces are detected in one image, each gets its own MusicXML file. Staff system indentation can sometimes be mistaken for the start of another piece.

With **Tesseract.js (Legacy)**, exact agreement with the upstream-based JVM reference has been verified on the test pages, including full MusicXML tree comparisons. The distributed app uses **tesseract-wasm (LSTM)** with an English model to reduce download size. LSTM output does not exactly match the JVM reference. The OCR backend can be selected at build time. Complex text layout and font fallback also have limitations.

### Offline edition

A single HTML file can bundle the engine, fonts and OCR model. Open `audiveris-offline.html` directly to use it without a server or network connection.

The published [single-file offline edition](https://1921music.github.io/web_audiveris/audiveris-offline.html) is also available. Save the link target as a file and open it locally.

The default tesseract-wasm edition is about 12.3 MB. Wasm and font files are gzip-compressed before being Base64-encoded and embedded. The regular web distribution's `index.html` requires HTTP serving.

### Distribution size

Measured for the default tesseract-wasm build on 2026-11-12. Values are decimal MB (1 MB = 1,000,000 bytes).

| Component | File size | Embedded payload¹ |
| --- | ---: | ---: |
| Recognition engine (`engine.wasm`) | 4.736 MB | 1.552 MB |
| FreeType renderer (`music-symbols.wasm`) | 0.497 MB | 0.231 MB |
| Rendering fonts (`fonts/fonts/`, 7 files) | 1.610 MB | 1.005 MB |
| Text fonts (`text-fonts/fonts/`, 8 files) | 3.392 MB | 1.849 MB |
| Music symbol classifier (`basic-classifier.zip`) | 0.396 MB | 0.396 MB |
| tesseract-wasm JavaScript (`lib.js`) | 0.098 MB | 0.025 MB |
| OCR Wasm, SIMD | 1.839 MB | 0.729 MB |
| OCR Wasm, non-SIMD fallback | 1.808 MB | 0.722 MB |
| English OCR model (`eng.traineddata.gz`) | 2.953 MB | 2.949 MB |

The **HTTP app distribution totals about 17.67 MB**, including other UI, runtime, metadata and license files. This is the sum of packaged file sizes, not a measured network transfer size; the optional offline HTML download is excluded. The **complete single-file offline edition is about 12.26 MB**. For comparison, the Tesseract.js single-file build is about 22.7 MB.

¹ Embedded payload means the stored size after optional gzip and before Base64 encoding. Wasm and fonts are always gzip-compressed. Identical payloads, including the DejaVu Sans font shared by the two font directories, are embedded only once. Required Java library code is compiled into `engine.wasm`; no separate JAR downloads are needed.

### Development and builds

The Java engine is compiled to WebAssembly with TeaVM. Score recognition and OCR run in separate Workers. Windows holds the authoritative source checkout, which is synchronized to a dedicated WSL environment for builds.

- [Development and build guide](wasm-compat/README.md)
- [WSL development environment](dev/wsl/README.md)
- [GitHub Pages publishing and updates](dev/pages/README.md)
- [Design principles](WASM-ENGINE-DESIGN.md)
- [Porting history and verification results](wasm-compat/DEVELOPMENT_HISTORY.md)

### Upstream and license

This repository is a web port based on **Audiveris 5.11.0** ([release tag `5.11.0`](https://github.com/Audiveris/audiveris/tree/5.11.0)). The pinned upstream `master` snapshot is commit [`7a36078e7ba0c006052c1f661b949cf9b729f505`](https://github.com/Audiveris/audiveris/commit/7a36078e7ba0c006052c1f661b949cf9b729f505), one documentation commit after that tag. The upstream README is preserved in [README.upstream.md](README.upstream.md).

The project is licensed under [GNU AGPL v3](LICENSE). Dependencies such as fonts and OCR components have their own licenses; license notices are included in the build artifacts.

---

## 日本語

[Audiveris](https://github.com/Audiveris/audiveris)の楽譜認識エンジンをWebAssemblyへ移植した、ブラウザで動く楽譜認識アプリです。楽譜画像を選択すると、認識結果をMusicXML 4.0としてダウンロードできます。

**公開URL：[https://1921music.github.io/web_audiveris/](https://1921music.github.io/web_audiveris/)**

UIは英語を主表示にし、日本語を小さく併記します。

### 使い方

1. 公開URLをWebAssembly GC対応のブラウザで開きます。動作検証にはChromiumを使用しています。
2. 楽譜画像を選択するか、画面にドラッグ＆ドロップします。
3. 「認識を開始」を押します。処理中は進捗を表示し、キャンセルもできます。
4. 認識が完了したら、MusicXMLをダウンロードします。

画像の認識処理はブラウザ内で行い、楽譜画像をサーバーへアップロードしません。認識エンジン・フォント・OCRモデルは初期読み込み時に取得します。

PNG・JPEG・WebP・BMPに対応しています。入力は1画像ずつで、上限は64 MiB・32 Miピクセル・各辺32,768ピクセルです。TIFF・PDFや、複数画像をまとめたブックの作成には対応していません。

### 認識結果について

自動認識には誤りが含まれます。出力したMusicXMLを楽譜編集ソフトなどで確認・修正してください。このWeb版には認識結果を修正するエディタはありません。

1枚の画像から複数の曲が検出されると、MusicXMLも複数になります。段の字下げを曲の区切りと誤判定する場合があります。

**Tesseract.js（Legacy）版では、検証用楽譜についてupstreamをもとにしたJVM参照との完全一致（MusicXMLツリー全体の比較）を確認しています。** 配布物を小さくするため、公開アプリでは英語モデルの **tesseract-wasm（LSTM）** を使っています。LSTM版の出力はJVM参照と完全には一致しません。OCRバックエンドはビルド時に切り替えられます。複雑な文字組みやフォントの代替にも制約があります。

### オフライン版

エンジン・フォント・OCRモデルを同梱した単一HTMLも作成できます。`audiveris-offline.html` を直接開くことで、サーバーやネットワーク接続なしで使えます。

公開済みの[オフライン版の単一HTML](https://1921music.github.io/web_audiveris/audiveris-offline.html)も利用できます。リンク先をファイルとして保存し、ローカルで開いてください。

既定のtesseract-wasm版は約12.3 MBです。Wasmとフォントはgzip圧縮後にBase64で埋め込みます。通常のWeb配布用 `index.html` はHTTP配信が必要です。

### 配布サイズ

2026-11-12時点の既定tesseract-wasmビルドの実測値です。MBは十進表記（1 MB = 1,000,000 bytes）です。

| 構成要素 | ファイルサイズ | 埋め込みペイロード¹ |
| --- | ---: | ---: |
| 認識エンジン（`engine.wasm`） | 4.736 MB | 1.552 MB |
| FreeType描画ライブラリ（`music-symbols.wasm`） | 0.497 MB | 0.231 MB |
| 描画用フォント（`fonts/fonts/`、7ファイル） | 1.610 MB | 1.005 MB |
| 文字用フォント（`text-fonts/fonts/`、8ファイル） | 3.392 MB | 1.849 MB |
| 音楽記号の分類モデル（`basic-classifier.zip`） | 0.396 MB | 0.396 MB |
| tesseract-wasmのJavaScript（`lib.js`） | 0.098 MB | 0.025 MB |
| OCR用Wasm・SIMD版 | 1.839 MB | 0.729 MB |
| OCR用Wasm・非SIMDフォールバック版 | 1.808 MB | 0.722 MB |
| 英語OCRモデル（`eng.traineddata.gz`） | 2.953 MB | 2.949 MB |

その他のUI・ランタイム・メタデータ・ライセンス文書を含め、**HTTP版の配布ファイル合計は約17.67 MB**です。これはファイルサイズの合計であり、実際の通信量ではありません。任意でダウンロードするオフラインHTMLは含みません。**単一HTML版は全体で約12.26 MB**です。比較用のTesseract.js単一HTML版は約22.7 MBです。

¹ 埋め込みペイロードは、必要に応じたgzip圧縮後・Base64化前の保存サイズです。Wasmとフォントは必ずgzip圧縮します。両フォントディレクトリで共有するDejaVu Sansなど、同一内容のペイロードは単一HTML内では一度だけ保持します。必要なJava依存ライブラリのコードは `engine.wasm` にコンパイルして含めるため、別途JARをダウンロードすることはありません。

### 開発・ビルド

JavaのエンジンをTeaVMでWebAssemblyへコンパイルし、楽譜認識とOCRをそれぞれWorkerで実行します。Windows側をソースの正本とし、専用WSL環境へ同期してビルドします。

- [開発・ビルドの入口](wasm-compat/README.md)
- [WSL開発環境の構築](dev/wsl/README.md)
- [GitHub Pagesへの公開・更新](dev/pages/README.md)
- [設計方針](WASM-ENGINE-DESIGN.md)
- [移植の開発経過・検証結果](wasm-compat/DEVELOPMENT_HISTORY.md)

### upstream・ライセンス

このリポジトリは **Audiveris 5.11.0**（[リリースタグ `5.11.0`](https://github.com/Audiveris/audiveris/tree/5.11.0)）をもとにしたWeb移植版です。基点は、そのタグから文書更新が1コミット進んだupstream `master` の [`7a36078e7ba0c006052c1f661b949cf9b729f505`](https://github.com/Audiveris/audiveris/commit/7a36078e7ba0c006052c1f661b949cf9b729f505) です。upstreamの説明は[README.upstream.md](README.upstream.md)に保存しています。

ライセンスは[GNU AGPL v3](LICENSE)です。フォント・OCRなどの依存物にはそれぞれのライセンスが適用され、ビルド成果物にもライセンス文書を同梱します。
