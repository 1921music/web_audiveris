# WSL開発環境

Windows側のリポジトリを正本とし、WSL内のコピーへrsyncしてビルドする。エンジンの移植実装は含まない。

## 構成

- ディストリビューション：`wasm_audiveris_dev`、Ubuntu 24.04 LTS、WSL2、x64。
- VHDX：リポジトリ直下の`WSL_image/wasm_audiveris_dev/ext4.vhdx`。このチェックアウトでは`G:\work\20261029\1921music\web_audiveris\WSL_image\wasm_audiveris_dev\ext4.vhdx`。
- 通常ユーザー：`dev`。専用開発環境内でパスワードなしのsudoを利用できる。
- ツール：Temurin JDK 25、Node.js 24 LTSとnpm、Git、rsync、Python、C/C++、CMake、Ninjaなど。
- Emscripten：バージョンを指定した場合のみ公式emsdkで追加する。TeaVMはプロジェクト側で選定し、GradleはリポジトリのWrapperを使用する。

Ubuntuイメージは24.04.5に固定し、公開SHA256と照合する。JDKとNodeは初回に指定メジャーの最新パッチを取得し、チェックサムを検証する。再実行時はインストール済み版を維持する。実際のバージョンは`/opt/wasm-audiveris/installed-versions.txt`に記録される。完全な再現性が必要になった段階でパッチ版も固定する。

## 構築

WindowsのPowerShellで、リポジトリ直下から実行する。WSL2が利用可能で、`wsl --list --quiet`が成功することが前提。必要なら先にWSLのインストール・更新とWindowsの再起動を行う。スクリプトはWindowsの機能設定や既存の他ディストリビューションを変更しない。

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Install
.\dev\wsl\Manage-WasmDev.ps1 Status
wsl -d wasm_audiveris_dev
```

PowerShell 5.1以降に対応する。実行ポリシーによりブロックされる場合は、内容を確認したうえで組織・端末の運用に沿って実行する。

Emscriptenが必要になったら、採用するリリース番号を明示して再実行する。

```powershell
# バージョン番号は例。採用版に置き換える。
.\dev\wsl\Manage-WasmDev.ps1 Install -EmsdkVersion 4.0.20
```

既存の同名環境が別の場所にある場合は停止する。同じ場所にある場合はセットアップを再実行できるが、対象環境は停止されるためビルド中には実行しない。途中で失敗した環境は自動削除しない。登録済みなら再実行し、未登録の非空ディレクトリが残った場合は内容を確認してから削除する。

## 同期とビルド

実行は専用cgroupを通し、すべての子プロセスを合計してCPU最大8論理CPU、メモリ最大8 GiB（7 GiBから回収圧力）、スワップ0に制限する。Gradleは並列ワーカー1つと使い捨てデーモンを使う。制限を設定できない場合は処理を開始しない。Windows全体の`.wslconfig`や他のディストリビューションの上限は変更しない。

コンソールへ転送する出力も1回につき1 MiBまでに制限する。上流ライブラリなどが大量の詳細ログを出しても、Windows側の表示・収集バッファを膨らませないようにする。超過後もパイプは読み捨てて処理を継続し、元の終了コードを返す。

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Sync
.\dev\wsl\Manage-WasmDev.ps1 Build
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(':app:test')
.\dev\wsl\Manage-WasmDev.ps1 Run -CommandArgs @('bash', '-lc', 'cd ~/work/web_audiveris/wasm-engine && npm run verify:browser')
```

同期先は`/home/dev/work/web_audiveris`。Windowsでの削除も反映するため、WSL側のコピーを直接編集しない。同期とビルドはロックで排他し、実行中の同期でソースが書き換わることを防ぐ。Windows側の編集中に完全なスナップショットを保証する仕組みではないため、ビルド前に編集内容を保存する。

`.git`、`WSL_image`、`build`、`.gradle`、`node_modules`などは同期対象外。ビルド出力や依存キャッシュはWSL内に保持する。Windows側のコミットIDと未コミット変更の有無を渡し、Gradleの初期化スクリプトで既存のコミット取得タスクだけ差し替える。Windows側のGit設定や履歴は変更しない。

成果物は当面`\\wsl.localhost\wasm_audiveris_dev\home\dev\work\web_audiveris`から参照できる。Windows側へ取り出す場合はGit管理外の`wsl-artifacts`などへ必要なものだけコピーする。Wasm成果物の配置・自動コピーは、ビルド対象が決まってから追加する。ブラウザ検証はWindows側のブラウザからWSLの開発サーバーへアクセスする方針とする。

`-LinuxUser`を変更した場合は、以後のInstall・Sync・Buildにも同じ値を指定する。

## 削除

```powershell
.\dev\wsl\Manage-WasmDev.ps1 Remove -WhatIf
.\dev\wsl\Manage-WasmDev.ps1 Remove
```

Removeは対象環境を登録解除し、指定のインストールディレクトリをVHDXごと完全に削除する。WSL内のソースコピー、キャッシュ、未回収の成果物も失われる。Windows側のソースと`WSL_image/downloads`内のダウンロード済みイメージは残す。

削除前に登録先を照合し、別の場所の同名環境やジャンクションなどを対象にしない。登録解除が失敗した場合はファイル削除へ進まない。`-WhatIf`で変更内容を確認でき、`-Confirm`で明示確認を付けられる。

## 検証

```powershell
.\dev\wsl\Test-WasmDev.ps1
```

この検証はPowerShell構文と削除・配置先・コマンド引数の制御を確認する。実ディストリビューションの作成・削除やダウンロードは行わない。実環境のセットアップとGradleビルドは別途実行する。

CPU・メモリ制限の実検証は、`Run`経由で`sudo python3 /mnt/g/work/20261029/1921music/web_audiveris/dev/wsl/test-limits.py`を実行する。CPU割当と子プロセスへの継承に加え、64 MiBの小さな検証用cgroupで上限超過時の終了を確認する。通常の8 GiBを使い切るテストは行わない。

参照：[MicrosoftのWSLコマンド](https://learn.microsoft.com/en-us/windows/wsl/basic-commands)、[Ubuntuのイメージとチェックサム](https://releases.ubuntu.com/24.04/)、[公式emsdk導入手順](https://emscripten.org/docs/getting_started/downloads.html)。
