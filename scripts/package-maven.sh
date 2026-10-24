#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Provide the immutable stable SemVer or RC version}"
[[ "$VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.(0|[1-9][0-9]*))?$ ]] || { echo 'Expected an immutable stable SemVer or RC version' >&2; exit 1; }
repository="debug-tools"
staging="build/maven/$VERSION"
output="build/release/$VERSION"
# Refuse to replace a prior candidate output; CI starts from a clean checkout.
[[ ! -e "$output" ]] || { echo "Candidate output already exists: $output" >&2; exit 1; }
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo 'Tracked source must be committed before packaging' >&2; exit 1; }
source_sha="$(git rev-parse HEAD)"
publications="debug-tools,debug-tools-android,debug-tools-iosarm64,debug-tools-iosx64,debug-tools-iossimulatorarm64,debug-tools-jvm,debug-tools-ohosarm64,debug-tools-kuikly,debug-tools-kuikly-ohosarm64"
rm -rf "$staging"
GROUP="com.github.gycrosskit.$repository" bash gradlew --init-script "$PWD/scripts/ci-repositories.gradle" publishAllPublicationsToStagingRepository --no-daemon --max-workers=1 --no-parallel --stacktrace
python3 scripts/jitpack-metadata.py "$staging"
python3 scripts/check-maven.py "$staging" "com.github.gycrosskit.$repository" "$VERSION" debug-tools,debug-tools-kuikly ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64 "$publications"
mkdir -p "$output"
archive="$repository-maven.tar.gz"
COPYFILE_DISABLE=1 tar --no-xattrs -czf "$output/$archive" -C "$staging" com
(cd "$output" && shasum -a 256 "$archive" > SHA256SUMS)
python3 - "$output" "$repository" "$VERSION" "$source_sha" "$publications" <<'RECEIPT'
import hashlib, io, json, subprocess, sys, tarfile, zipfile
from pathlib import Path
output, repository, version, source_sha, publications = sys.argv[1:]
output = Path(output)
archive = output / (repository + '-maven.tar.gz')
with tarfile.open(archive) as bundle:
    for member in bundle.getmembers():
        assert member.isfile() or member.isdir(), member.name
        assert member.name.startswith('com/') or member.name == 'com', member.name
        assert '..' not in Path(member.name).parts, member.name
        assert not Path(member.name).name.startswith('._'), member.name
        if member.isfile() and Path(member.name).suffix in ('.jar', '.aar', '.zip', '.klib'):
            with zipfile.ZipFile(io.BytesIO(bundle.extractfile(member).read())) as zipped:
                assert zipped.testzip() is None, member.name
receipt = {'repository': repository, 'version': version, 'source_sha': source_sha,
           'source_tree': subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip(),
           'archive': archive.name, 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
           'publications': publications.split(','), 'metadata_and_hashes_checked': True}
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == source_sha
assert not subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], text=True)
(output / 'source-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
RECEIPT
printf 'Candidate files: %s\n' "$output"
