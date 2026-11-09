#!/usr/bin/env bash
set -euo pipefail

# Every build/test command and all descendants share this ceiling, even across launches.
group=/sys/fs/cgroup/wasm_audiveris_dev
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
as_root=false
if [[ ${1:-} == --as-root ]]; then
    [[ $EUID == 0 && ${WSL_DISTRO_NAME:-} == wasm_audiveris_dev ]] || exit 1
    as_root=true
    shift
    cwd=$PWD
elif [[ ${1:-} != --root ]]; then
    [[ ${WSL_DISTRO_NAME:-} == wasm_audiveris_dev ]] || { echo 'Use the dedicated wasm_audiveris_dev WSL distro' >&2; exit 1; }
    [[ $# -gt 0 ]] || { echo 'A command is required' >&2; exit 1; }
    exec sudo -- bash "$0" --root "$PWD" "$@"
else
    shift
    cwd=${1:?Working directory required}
    shift
    [[ $EUID == 0 && ${SUDO_UID:-0} -gt 0 ]] || { echo 'Run as the development user via sudo' >&2; exit 1; }
fi
[[ $# -gt 0 ]] || { echo 'A command is required' >&2; exit 1; }
[[ $(stat -fc %T /sys/fs/cgroup) == cgroup2fs ]] || { echo 'cgroup v2 is required; refusing an unlimited launch' >&2; exit 1; }

exec 8>/run/lock/wasm-audiveris-resources.lock
flock 8
for controller in cpu cpuset memory pids; do
    grep -qw "$controller" /sys/fs/cgroup/cgroup.controllers || { echo "Missing cgroup controller: $controller" >&2; exit 1; }
    if ! grep -qw "$controller" /sys/fs/cgroup/cgroup.subtree_control; then
        printf '+%s\n' "$controller" > /sys/fs/cgroup/cgroup.subtree_control
    fi
done
mkdir -p "$group"
cpus=$(python3 -c 'import os; print(",".join(map(str, sorted(os.sched_getaffinity(0))[:3])))')
printf '%s\n' "$(cat /sys/fs/cgroup/cpuset.mems.effective)" > "$group/cpuset.mems"
printf '%s\n' "$cpus" > "$group/cpuset.cpus"
printf '300000 100000\n' > "$group/cpu.max"
printf '8589934592\n' > "$group/memory.max"
printf '7516192768\n' > "$group/memory.high"
printf '0\n' > "$group/memory.swap.max"
printf '1\n' > "$group/memory.oom.group"
printf '256\n' > "$group/pids.max"
printf '+cpu +cpuset +memory +pids\n' > "$group/cgroup.subtree_control"
mkdir -p "$group/jobs"
printf '%s\n' "$$" > "$group/jobs/cgroup.procs"
flock -u 8
exec 8>&-

printf 'Resource limits: CPUs %s (max 3), memory 8 GiB total, swap 0\n' "$cpus"
cd "$cwd"
if $as_root; then
    limited_command=(taskset -c "$cpus" "$@")
else
user_home=$(getent passwd "$SUDO_UID" | cut -d: -f6)
limited_command=(taskset -c "$cpus" runuser -u "$SUDO_USER" -- env \
    HOME="$user_home" USER="$SUDO_USER" LOGNAME="$SUDO_USER" \
    WSL_DISTRO_NAME=wasm_audiveris_dev \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    OMP_NUM_THREADS=3 OPENBLAS_NUM_THREADS=3 MKL_NUM_THREADS=3 UV_THREADPOOL_SIZE=1 \
    JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=3 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -XX:CICompilerCount=2' \
    NODE_OPTIONS='--max-old-space-size=384' \
    bash -c 'source /opt/wasm-audiveris/env.sh; exec "$@"' limited-command "$@")
fi
# Bound host-side tool buffers too; large debug logs must not exhaust Windows memory.
set +e
"${limited_command[@]}" 2>&1 | python3 "$script_dir/limit-output.py"
result=${PIPESTATUS[0]}
exit "$result"
