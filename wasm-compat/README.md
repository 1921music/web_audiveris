# WebAssemblyエンジンの開発・ビルド

アプリの概要・使い方・公開URLは[プロジェクトのREADME](../README.md)を参照してください。移植中の調査記録、過去の数値、個別コンポーネントの検証は[開発経過](DEVELOPMENT_HISTORY.md)にまとめています。

## ビルド

[WSL開発環境](../dev/wsl/README.md)を構築し、Emscripten 4.0.20を導入してから、Windowsのリポジトリ直下で実行します。

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Sync
.\dev\wsl\Manage-WasmDev.ps1 Run -CommandArgs @(
  'bash', '-lc', 'cd ~/work/web_audiveris && npm ci --prefix wasm-compat')
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
  '-I', 'dev/teavm-audit/audit.init.gradle',
  '-I', 'dev/teavm-audit/page-recognition.init.gradle',
  '-I', 'dev/teavm-audit/engine.init.gradle', 'packageWorkerOffline')
```

ランチャーは、ビルドとその子プロセスを合計で最大8論理CPU・メモリ8 GiB・スワップ0に制限します。

出力はWSLの `/home/dev/work/web_audiveris/build/wasm-engine/` 以下です。

| タスク | 出力 | 用途 |
| --- | --- | --- |
| `packageWorkerEngine` | `package/` | 外部からOCRを注入するエンジンとホストAPI |
| `packageWorkerOcr` | `package/` | UIと選択したOCRを含むHTTP配布用ファイル |
| `packageWorkerOffline` | `offline/audiveris-offline.html` | オフラインで直接開く単一HTML。HTTP配布用ファイルも生成 |

通常のHTTP配布は `package/` の内容をそのまま公開します。ローカルでは次のコマンドで配信し、`http://127.0.0.1:8765/` を開きます。

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Run -CommandArgs @(
  'python3', '-u', '-m', 'http.server', '8765', '--bind', '127.0.0.1',
  '--directory', '/home/dev/work/web_audiveris/build/wasm-engine/package')
```

## OCRの選択

既定は **tesseract-wasm** です。upstream/JVMとの一致検証用にTesseract.jsを使う場合は、Gradle引数へ `-PocrBackend=tesseract-js` を追加します。通常版を明示する場合は `-PocrBackend=tesseract-wasm` を指定します。

選択したバックエンドのライブラリ・OCRコア・英語モデルだけを配布物へ含めます。実行時にOCRバックエンドを切り替えるUIはありません。使用したバックエンド・モデル・パッケージバージョンは `package/ocr/assets.json` に記録します。

Tesseract.js版は検証したページでJVM参照と一致しています。LSTM版では文字や音楽認識の結果に差があり、未知のフォント属性を標準書体として扱うなどの制約もあります。

## 設計と検証

- [エンジンの設計方針](../WASM-ENGINE-DESIGN.md)
- [開発経過・ホストAPI・詳細な検証手順](DEVELOPMENT_HISTORY.md)
- [エンジンの検証記録](engine-results/)
- [OCRバックエンドの比較記録](ocr-backend-results/)
- [TeaVM依存APIの解析](../dev/teavm-audit/README.md)

過去の検証記録は、その記録に対応するソースと設定での結果です。開発経過に載る途中段階のクラス数・診断数・サイズは、現在のビルド全体の値を示すものではありません。
