"""Bound console output forwarded to Windows, while draining the child pipe fully."""
import sys

remaining = 1024 * 1024
truncated = False
while chunk := sys.stdin.buffer.read1(65536):
    shown = chunk[:remaining]
    if shown:
        sys.stdout.buffer.write(shown)
        sys.stdout.buffer.flush()
        remaining -= len(shown)
    if len(chunk) > len(shown) and not truncated:
        sys.stdout.buffer.write(b'\n[console output capped at 1 MiB; remaining output discarded]\n')
        sys.stdout.buffer.flush()
        truncated = True
