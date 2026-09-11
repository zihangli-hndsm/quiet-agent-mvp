"""Collect only this MVP's synthetic source and a named job over USB."""
import argparse
import io
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--job', required=True)
p.add_argument('--output', required=True)
p.add_argument('--source-mode', choices=['demo', 'saf-sample'], default='demo')
a = p.parse_args()
if not re.fullmatch(r'job-[0-9]+-[a-f0-9]{8}', a.job):
    p.error('Invalid job id')
out = Path(a.output)
out.mkdir(parents=True, exist_ok=True)
source = '/sdcard/Android/data/app.quietagent/files/demo' if a.source_mode == 'demo' else '/sdcard/Documents/QuietAgentMVP-SAF-20260911'
for name, remote in [('source', source),
                     ('result', 'files/jobs/' + a.job)]:
    identity = [] if name == 'source' else ['run-as', 'app.quietagent']
    blob = subprocess.check_output([a.adb, 'exec-out'] + identity + ['tar', '-C', remote, '-cf', '-', '.'])
    target = out / name
    target.mkdir(exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(blob)) as t:
        for member in t:
            rel = PurePosixPath(member.name)
            if rel.is_absolute() or '..' in rel.parts or '\\' in member.name:
                raise ValueError('Unsafe archive path')
            dest = target.joinpath(*rel.parts)
            if member.isdir():
                dest.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(t.extractfile(member).read())
            else:
                raise ValueError('Unsupported archive entry')
print('Collected synthetic source and job:', a.job)
