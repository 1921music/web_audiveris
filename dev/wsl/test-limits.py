"""Run through Manage-WasmDev.ps1 Run -CommandArgs @('sudo', 'python3', <this file>)."""
import json
import os
from pathlib import Path
import subprocess
import sys

group = Path('/sys/fs/cgroup/wasm_audiveris_dev')
assert os.geteuid() == 0, 'sudo is needed only to create the small OOM probe cgroup'
assert 1 <= len(os.sched_getaffinity(0)) <= 3
assert (group / 'cpu.max').read_text().strip() == '300000 100000'
assert int((group / 'memory.max').read_text()) == 8 * 1024**3
assert int((group / 'memory.high').read_text()) == 7 * 1024**3
assert (group / 'memory.swap.max').read_text().strip() == '0'
assert '/wasm_audiveris_dev/jobs' in Path('/proc/self/cgroup').read_text()
child_membership = subprocess.check_output(['cat', '/proc/self/cgroup'], text=True)
assert '/wasm_audiveris_dev/jobs' in child_membership
helper_dir = Path(__file__).resolve().parent
small_output = subprocess.run([sys.executable, str(helper_dir / 'limit-output.py')], input=b'ok\n', capture_output=True, check=True)
assert small_output.stdout == b'ok\n'
large_output = subprocess.run([sys.executable, str(helper_dir / 'limit-output.py')], input=b'x' * (2 * 1024**2), capture_output=True, check=True)
assert len(large_output.stdout) < 1024**2 + 128 and b'console output capped' in large_output.stdout
exit_probe = subprocess.run(['bash', str(helper_dir / 'run-limited.sh'), '--as-root', 'bash', '-c', 'exit 17'],
                            env={**os.environ, 'WSL_DISTRO_NAME': 'wasm_audiveris_dev'}, capture_output=True)
assert exit_probe.returncode == 17, (exit_probe.returncode, exit_probe.stderr.decode())

# Prove enforcement with a 64 MiB child budget, never by exhausting the 8 GiB budget.
probe = group / f'probe-{os.getpid()}'
probe.mkdir()
try:
    (probe / 'memory.max').write_text(str(64 * 1024**2))
    (probe / 'memory.swap.max').write_text('0')
    (probe / 'memory.oom.group').write_text('1')
    command = 'echo $$ > "$1/cgroup.procs"; exec python3 -c "data=bytearray(128*1024*1024); data[::4096]=bytes(len(data[::4096]))"'
    process = subprocess.run(['bash', '-c', command, 'memory-probe', str(probe)], timeout=20)
    events = dict(line.split() for line in (probe / 'memory.events').read_text().splitlines())
    assert process.returncode == -9 and int(events['oom_kill']) >= 1, (process.returncode, events)
    print(json.dumps({'allowedCpus': sorted(os.sched_getaffinity(0)), 'memoryMaxBytes': 8 * 1024**3,
                      'childInheritance': True, 'smallOomProbeKilled': True,
                      'consoleOutputBounded': True, 'exitCodePreserved': True}))
finally:
    probe.rmdir()
