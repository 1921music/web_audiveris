#!/usr/bin/env bash
set -euo pipefail

user=${1:?Linux user required}
sdk_version=${2:-none}
[[ "$user" =~ ^[a-z][a-z0-9_-]{0,30}$ && "$user" != root ]] || { echo 'Invalid development user' >&2; exit 1; }
[[ "$sdk_version" == none || "$sdk_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 1
[[ $(id -u) == 0 ]] || { echo 'Run bootstrap as root' >&2; exit 1; }
source /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 24.04 ]] || { echo 'Ubuntu 24.04 required' >&2; exit 1; }
[[ $(uname -m) == x86_64 ]] || { echo 'x86_64 required' >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    ca-certificates curl git rsync unzip zip xz-utils jq sudo \
    build-essential cmake ninja-build pkg-config python3 python3-venv python3-pip \
    fontconfig libfreetype6 libxi6 libxrender1 libxtst6

if ! id "$user" &>/dev/null; then useradd --create-home --shell /bin/bash "$user"; fi
user_home=$(getent passwd "$user" | cut -d: -f6)
[[ "$user_home" == "/home/$user" ]] || { echo 'Unexpected user home' >&2; exit 1; }
printf '%s ALL=(ALL) NOPASSWD:ALL\n' "$user" > "/etc/sudoers.d/wasm-audiveris-dev"
chmod 0440 /etc/sudoers.d/wasm-audiveris-dev
visudo -cf /etc/sudoers.d/wasm-audiveris-dev

mkdir -p /opt/wasm-audiveris
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT

# Resolve JDK 25 once. Re-running bootstrap keeps the installed patch version.
if [[ ! -x /opt/wasm-audiveris/jdk-25/bin/java ]]; then
    curl --fail --location --retry 3 \
        'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse' \
        -o "$tmp/jdk.json"
    url=$(jq -er '.[0].binary.package.link' "$tmp/jdk.json")
    checksum=$(jq -er '.[0].binary.package.checksum' "$tmp/jdk.json")
    curl --fail --location --retry 3 "$url" -o "$tmp/jdk.tar.gz"
    printf '%s  %s\n' "$checksum" "$tmp/jdk.tar.gz" | sha256sum --check -
    mkdir "$tmp/jdk"
    tar -xzf "$tmp/jdk.tar.gz" -C "$tmp/jdk" --strip-components=1
    mv "$tmp/jdk" /opt/wasm-audiveris/jdk-25
    cp "$tmp/jdk.json" /opt/wasm-audiveris/jdk-download.json
fi

# Pin the Node major; record the exact installed version and verify its archive.
if [[ ! -x /opt/wasm-audiveris/node-24/bin/node ]]; then
    curl --fail --location --retry 3 https://nodejs.org/dist/index.json -o "$tmp/node.json"
    version=$(jq -er '[.[] | select(.version | startswith("v24.")) | select(.lts != false)][0].version' "$tmp/node.json")
    archive="node-$version-linux-x64.tar.xz"
    curl --fail --location --retry 3 "https://nodejs.org/dist/$version/SHASUMS256.txt" -o "$tmp/node-sums"
    curl --fail --location --retry 3 "https://nodejs.org/dist/$version/$archive" -o "$tmp/$archive"
    checksum=$(awk -v name="$archive" '$2 == name {print $1}' "$tmp/node-sums")
    [[ "$checksum" =~ ^[0-9a-f]{64}$ ]] || { echo 'Missing Node checksum' >&2; exit 1; }
    printf '%s  %s\n' "$checksum" "$tmp/$archive" | sha256sum --check -
    mkdir "$tmp/node"
    tar -xJf "$tmp/$archive" -C "$tmp/node" --strip-components=1
    mv "$tmp/node" /opt/wasm-audiveris/node-24
fi

cat > /opt/wasm-audiveris/env.sh <<'ENV'
export JAVA_HOME=/opt/wasm-audiveris/jdk-25
if [[ -f "$HOME/tools/emsdk/emsdk_env.sh" ]]; then
    source "$HOME/tools/emsdk/emsdk_env.sh" >/dev/null 2>&1
fi
export PATH="/opt/wasm-audiveris/node-24/bin:$JAVA_HOME/bin:$PATH"
ENV
printf '%s\n' 'source /opt/wasm-audiveris/env.sh' > /etc/profile.d/wasm-audiveris.sh
# Bash interactive non-login shells also need the toolchain environment.
touch "$user_home/.bashrc"
if ! grep -qF 'source /opt/wasm-audiveris/env.sh' "$user_home/.bashrc"; then
    printf '\nsource /opt/wasm-audiveris/env.sh\n' >> "$user_home/.bashrc"
fi
chown "$user:$(id -gn "$user")" "$user_home/.bashrc"
install -d -o "$user" -g "$(id -gn "$user")" "$user_home/work" "$user_home/tools"

if [[ "$sdk_version" != none ]]; then
    runuser -u "$user" -- bash -s -- "$sdk_version" <<'SDK'
set -euo pipefail
cd "$HOME/tools"
if [[ ! -d emsdk/.git ]]; then git clone https://github.com/emscripten-core/emsdk.git; fi
cd emsdk
git fetch --tags origin
git checkout --detach "$1"
./emsdk install "$1"
./emsdk activate "$1"
SDK
fi

# This dedicated imported distro has no interactive first-run account setup.
cat > /etc/wsl.conf <<CONF
[user]
default=$user
[interop]
appendWindowsPath=false
CONF

source /opt/wasm-audiveris/env.sh
{
    cat /etc/os-release
    java -version 2>&1
    node --version
    npm --version
    if [[ -f "$user_home/tools/emsdk/emsdk_manifest.json" ]]; then
        runuser -u "$user" -- git -C "$user_home/tools/emsdk" describe --tags --always
    fi
    dpkg-query -W
} > /opt/wasm-audiveris/installed-versions.txt
echo 'Bootstrap complete. Installed versions: /opt/wasm-audiveris/installed-versions.txt'
