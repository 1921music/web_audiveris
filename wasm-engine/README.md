# Wasm認識エンジン

TeaVM Wasm GCで動かす、UIを持たないエンジンの移植先。デスクトップ版のGradle構成から独立させ、認識処理に必要なコードだけを含める。

現在の対象は適応的二値化と、スケール推定のうち線幅・五線間隔・連桁の太さの測定。五線の位置検出、音符認識、OCR、MusicXML出力はまだ移植していない。スケール推定の成功は五線位置の検出完了を意味しない。

## 実装の境界

- 入力はGRAY8画素列、幅、高さ、行ストライド。デコードはエンジン外で行う。
- 二値化は上流の`AdaptiveFilter`と`VerticalFilter`の式と循環積分列を移植した。既定値は窓の半径18、平均係数0.7、標準偏差係数0.9。
- スケール推定は`ScaleBuilder`の垂直ランのヒストグラムと線幅・間隔・連桁のピーク選択を移植した。連桁の太さは実測と推定を区別する。ユーザー指定スケールは未対応。
- `Range`、`IntegerFunction`、`HiLoPeakFinder`は上流の計算部分を利用し、描画、XMLアノテーション、ログを除いた。比較元の初期upstreamコミットは`7a36078`。
- 画素はdirect NIOバッファでJSとまとめて受け渡す。Worker間へ転送する出力は独立した配列にコピーし、Wasmメモリ自体は転送しない。
- 計算は列の区切りで制御を返す。グレースケール入力と積分表は二値化後に解放できる構造とした。

## Workerの暫定プロトコル

`build/dist/engine-worker.js`を通常のWeb Workerとして起動する。初期化後に`ready`が返る。現時点では1 Workerで1ページずつ処理し、独立したWorkerを複数起動できる。

```javascript
const worker = new Worker('/engine-worker.js');
worker.onmessage = ({ data }) => {
    // ready, progress, done, failed, cancelled
};
// readyを受け取ってから送る。pixelsはGRAY8のArrayBuffer。
worker.postMessage({
    type: 'start', jobId: 'job-1', pageId: 'page-1', generation: 1,
    width, height, stride: width, targetStage: 'SCALE', pixels
}, [pixels]);
```

`targetStage`は`BINARY`または`SCALE`。`done`には二値画像のArrayBufferと、SCALEの場合は`scale`を返す。scaleの暫定配列は、線幅、主間隔、小間隔の順にそれぞれ`min, main, max`を並べ、その後に連桁の太さ、小さい連桁の太さ、連桁が推定値かどうか（1/0）を並べる。存在しない小間隔・小さい連桁は0とする。API名や型は移植範囲に合わせて変更できる。

キャンセル要求は`type: 'cancel'`と同じ`jobId, pageId, generation`を送る。計算の区切りでキャンセルでき、以後は同じWorkerを再利用できる。実装済み工程に外部非同期処理はまだないため、OCRの要求・応答はTEXTS工程の移植時に接続する。

## WSLでのビルドと検証

Windows側のリポジトリ直下から実行する。

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @('-p', 'wasm-engine', 'test', 'dist')

.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
    '-p', 'wasm-engine', 'test', 'verifyWorker',
    '-Psamples=/mnt/g/work/20261029/1921music/score_samples'
)
```

JVMテストでは、上流の画像フィルタのソースを変更せず別途コンパイルする。テスト専用の設定シムは既定の窓サイズを提供し、Wasmには含めない。画像デコード、上流フィルタ、比較用ライブラリも配布対象外。

`verifyWorker`は全サンプルで、上流JVMフィルタとWasmの二値画像を比較し、移植版JVMとWasmのスケール結果・失敗を比較する。キャンセル後の再利用と2 Workerでの同時実行も確認する。画像サイズが大きい場合から小さい場合へのバッファ再利用も対象に含む。

元のJVMアプリ全体との比較は、上の入力生成後に実行する。

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(
    'upstreamReference', '-I', 'wasm-engine/tools/upstream-reference.init.gradle'
)
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @('-p', 'wasm-engine', 'compareUpstream')
```

こちらは移植側でスケールを測定できた入力について、同じグレースケール画像を元のアプリへ渡し、保存された`.omr`内の二値画像・線幅・間隔と比較する。上流のGRID工程の基準出力も必要なら`-PreferenceStep=GRID`を追加できる。

Chromiumの実ブラウザ検証は、WSL内の`~/work/web_audiveris/wasm-engine`で実行する。

```bash
bash ../dev/wsl/run-limited.sh npm ci
bash ../dev/wsl/run-limited.sh npx playwright install --with-deps chromium
npm run verify:browser
```

## 成果物と測定

WasmとWorkerの配布ファイルは`build/dist`。サンプル画像のコピーや比較データは`build/sample-reference`に生成し、Gitへ追加しない。

- `build/verification.json`：Node Workerでの一致検証、処理時間、初期化時間、Wasm・ランタイムのサイズ。
- `build/browser-verification.json`：Chromiumでの二値画像のハッシュ照合と処理時間。
- `build/upstream-comparison.json`：元のJVMアプリ全体との比較件数。

時間は初期の動作確認値であり、統計的な性能ベンチマークではない。Worker側の時間には受け渡しと再開処理も含む。現在のサイズには五線検出以降の処理やOCRモデルは含まれない。Nodeの最大RSSは検証データと2 Workerを含む検証プロセス全体の値であり、エンジン単体のピークメモリは今後分離して評価する。
