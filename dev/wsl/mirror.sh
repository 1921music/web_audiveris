#!/usr/bin/env bash
set -euo pipefail

mode=${1:?sync or build required}
source_dir=$(realpath -e -- "${2:?Windows source required}")
revision=${3:?Source revision required}
shift 3
[[ "$mode" == sync || "$mode" == build ]] || exit 1
[[ "$revision" =~ ^[0-9a-f]{40,64}(-dirty)?$ ]] || { echo 'Invalid source revision' >&2; exit 1; }
[[ "$source_dir" == /mnt/* && -f "$source_dir/settings.gradle" && -d "$source_dir/dev/wsl" ]] || {
    echo 'Expected the Windows Audiveris source directory' >&2; exit 1;
}

source /opt/wasm-audiveris/env.sh
mirror="$HOME/work/web_audiveris"
mkdir -p "$mirror"
[[ ! -L "$HOME/work" && ! -L "$mirror" && $(realpath -e "$mirror") == "$mirror" ]] || {
    echo 'Refusing a redirected mirror directory' >&2; exit 1;
}
# Prevent concurrent sync/build from modifying sources while Gradle is reading them.
exec 9>"$HOME/work/.web_audiveris.lock"
flock -n 9 || { echo 'Another sync/build is already running' >&2; exit 1; }

# Excluded paths are protected from --delete, preserving Linux build caches.
# --checksum avoids missed same-size edits due to timestamp granularity on /mnt.
rsync -rlt --checksum --delete --safe-links --no-perms --omit-dir-times \
    --exclude='.git' --exclude='WSL_image/' --exclude='wsl-artifacts/' \
    --exclude='.gradle/' --exclude='build/' --exclude='node_modules/' \
    --exclude='.venv/' --exclude='.idea/' --exclude='.vscode/' \
    "$source_dir/" "$mirror/"
printf '%s\n' "$revision" > "$HOME/work/web_audiveris-source-revision.txt"
echo "Synchronized to $mirror ($revision)"

if [[ "$mode" == build ]]; then
    export AUDIVERIS_SOURCE_COMMIT="$revision"
    cd "$mirror"
    # Existing Windows checkouts can still contain CRLF despite .gitattributes.
    sed -i 's/\r$//' ./gradlew
    # The mirror has no .git. Override only the existing commit-query task.
    bash ./gradlew --init-script "$mirror/dev/wsl/source-commit.init.gradle" "$@" \
        --no-daemon --max-workers=1 --no-parallel \
        '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=8'
fi
