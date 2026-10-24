"""Exercise the production version gate only; never invoke packaging or SDKs."""
import os
from pathlib import Path
import subprocess

script = Path(__file__).resolve().with_name('package-maven.sh')
gate = script.read_text().split('repository=', 1)[0]
assert 'Expected an immutable stable SemVer or RC version' in gate
accepted = ('0.0.0', '1.0.0', '10.20.30', '0.2.0-rc.7', '1.0.0-rc.0', '1.0.0-rc.12')
rejected = ('', '1', '1.0', '01.0.0', '1.01.0', '1.0.01', '1.0.0-rc.01',
            '1.0.0-SNAPSHOT', '1.0.0-rc.1-SNAPSHOT', '1.0.0-beta.1', '1.0.0+build.1',
            '../1.0.0', '1.0.0/../x', '1.0.0;true', '1.0.0$(true)', '1.0.0`true`',
            '1.0.0\n', ' 1.0.0', '1.0.0 ')
for version in accepted + rejected:
    result = subprocess.run(['bash', '-c', gate, str(script)],
                            env=dict(os.environ, VERSION=version),
                            capture_output=True, text=True, timeout=5)
    assert (result.returncode == 0) == (version in accepted), (version, result.stderr)
missing = dict(os.environ)
missing.pop('VERSION', None)
assert subprocess.run(['bash', '-c', gate, str(script)], env=missing,
                      capture_output=True, timeout=5).returncode != 0
print(f'PASS: {len(accepted)} accepted, {len(rejected)} rejected, missing VERSION rejected; no packaging/SDK calls')
