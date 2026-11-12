# Web Audiveris

[Audiveris](https://github.com/Audiveris/audiveris)の楽譜認識エンジンをWebAssemblyへ移植した、ブラウザで動く楽譜認識アプリです。楽譜画像を選択すると、認識結果をMusicXML 4.0としてダウンロードできます。

**公開URL：[https://1921music.github.io/web_audiveris/](https://1921music.github.io/web_audiveris/)**

## 使い方

1. 公開URLをWebAssembly GC対応のブラウザで開きます。動作検証にはChromiumを使用しています。
2. 楽譜画像を選択するか、画面にドラッグ＆ドロップします。
3. 「認識を開始」を押します。処理中は進捗を表示し、キャンセルもできます。
4. 認識が完了したら、MusicXMLをダウンロードします。

画像の認識処理はブラウザ内で行い、楽譜画像をサーバーへアップロードしません。認識エンジン・フォント・OCRモデルは初期読み込み時に取得します。

PNG・JPEG・WebP・BMPに対応しています。入力は1画像ずつで、上限は64 MiB・32 Miピクセル・各辺32,768ピクセルです。TIFF・PDFや、複数画像をまとめたブックの作成には対応していません。

## 認識結果について

自動認識には誤りが含まれます。出力したMusicXMLを楽譜編集ソフトなどで確認・修正してください。このWeb版には認識結果を修正するエディタはありません。

1枚の画像から複数の曲が検出されると、MusicXMLも複数になります。段の字下げを曲の区切りと誤判定する場合があります。

文字認識は、既定で英語モデルの **tesseract-wasm（LSTM）** を使います。upstream/JVM版との認識結果・MusicXMLの一致検証には **Tesseract.js（Legacy）** を使い、ビルド時に切り替えます。LSTM版の出力はupstream/JVM版と完全には一致しません。複雑な文字組みやフォントの代替にも制約があります。

## オフライン版

エンジン・フォント・OCRモデルを同梱した単一HTMLも作成できます。`audiveris-offline.html` を直接開くことで、サーバーやネットワーク接続なしで使えます。

既定のtesseract-wasm版は約12.3 MBです。Wasmとフォントはgzip圧縮後にBase64で埋め込みます。通常のWeb配布用 `index.html` はHTTP配信が必要です。

## 開発・ビルド

JavaのエンジンをTeaVMでWebAssemblyへコンパイルし、楽譜認識とOCRをそれぞれWorkerで実行します。Windows側をソースの正本とし、専用WSL環境へ同期してビルドします。

- [開発・ビルドの入口](wasm-compat/README.md)
- [WSL開発環境の構築](dev/wsl/README.md)
- [GitHub Pagesへの公開・更新](dev/pages/README.md)
- [設計方針](WASM-ENGINE-DESIGN.md)
- [移植の開発経過・検証結果](wasm-compat/DEVELOPMENT_HISTORY.md)

## upstream・ライセンス

このリポジトリは[Audiveris](https://github.com/Audiveris/audiveris)をもとにしたWeb移植版です。upstreamの説明は[README.upstream.md](README.upstream.md)に保存しています。

ライセンスは[GNU AGPL v3](LICENSE)です。フォント・OCRなどの依存物にはそれぞれのライセンスが適用され、ビルド成果物にもライセンス文書を同梱します。
