# GitHub Pagesへの公開

公開URLは <https://1921music.github.io/web_audiveris/>。ソースは `wasm` ブランチ、公開する静的ファイルは `gh-pages` ブランチで管理します。GitHub Pagesの配信元は `gh-pages` のルートです。

## 更新手順

1. [ビルド手順](../../wasm-compat/README.md)で既定のtesseract-wasm版を作成・検証します。
2. HTTP配布物を `wsl-artifacts/wasm-engine/`、単一HTMLと対応する `build.json` を `wsl-artifacts/wasm-engine-offline/` に配置します。
3. `wasm` ブランチでソースをコミットし、次を実行します。

```powershell
git push origin wasm
.\dev\pages\Publish-Pages.ps1
```

公開前の成果物確認だけを行う場合は `-PrepareOnly` を指定します。確認用ファイルはGit管理外の `build/pages/` 以下に作成します。

スクリプトは既存のintegrity manifestでファイルのサイズとSHA-256を照合し、そこに列挙された実行時アセットと検証済み単一HTMLだけを配置します。Tesseract.js版は公開対象として受け付けません。ソースのチェックアウトとインデックスを維持したまま、`gh-pages` に通常のコミットとpushを行います。force pushは行いません。

GitHub側の初回設定は、リポジトリの **Settings → Pages → Deploy from a branch → gh-pages / (root)** で行います。その後の更新は `gh-pages` へのpushで公開されます。`deployment.json` には公開元のソースコミット、OCRバックエンド、アセットのハッシュを記録します。

公開後は次を確認します。

- トップページと `ocr/assets.json` が取得でき、バックエンドが `tesseract-wasm` になっていること。
- 実際の楽譜画像を認識し、MusicXMLをダウンロードできること。
- [単一HTML](https://1921music.github.io/web_audiveris/audiveris-offline.html)も取得できること。
