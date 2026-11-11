#!/usr/bin/env bash
set -euo pipefail

# Optional native/Wasm comparison toolchain; invoke through run-limited.sh.
version=${1:-4.0.20}
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected an explicit emsdk release' >&2; exit 1; }
[[ ${WSL_DISTRO_NAME:-} == wasm_audiveris_dev && $EUID != 0 ]] || exit 1
grep -q '/wasm_audiveris_dev/' /proc/self/cgroup || { echo 'Use the resource-capped launcher' >&2; exit 1; }
mkdir -p "$HOME/tools"
sdk="$HOME/tools/emsdk"
if [[ ! -d "$sdk/.git" ]]; then
    [[ ! -e "$sdk" ]] || { echo 'Existing non-Git emsdk directory; refusing to overwrite' >&2; exit 1; }
    git clone --depth 1 --branch "$version" https://github.com/emscripten-core/emsdk.git "$sdk"
fi
cd "$sdk"
git diff --quiet && git diff --cached --quiet || { echo 'Existing emsdk has local changes' >&2; exit 1; }
if ! git rev-parse --verify "refs/tags/$version" >/dev/null 2>&1; then
    git fetch --depth 1 origin "refs/tags/$version:refs/tags/$version"
fi
git checkout --detach "$version"
./emsdk install "$version"
./emsdk activate "$version"
git rev-parse HEAD
echo "emsdk $version ready; new capped commands load emsdk_env.sh through the existing environment hook."
