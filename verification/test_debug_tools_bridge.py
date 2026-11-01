#!/usr/bin/env python3
"""使用已有缓存编译真实 sender 与 Kuikly 2.29.1；只替换 native delegate，不下载或运行 SDK 构建。"""
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'


def artifact(group, name, version, suffix='.jar'):
    matches = sorted((CACHE / group / name / version).glob(f'*/{name}-{version}{suffix}'))
    if not matches:
        raise SystemExit(f'缺少已缓存依赖 {group}:{name}:{version}{suffix}；本入口不会下载')
    return matches[0]


def main():
    compiler = [artifact('org.jetbrains.kotlin', name, version) for name, version in [
        ('kotlin-compiler-embeddable', '2.2.21'), ('kotlin-script-runtime', '2.2.21'),
        ('kotlin-reflect', '1.6.10'), ('kotlin-daemon-embeddable', '2.2.21')]]
    libraries = [artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21-1.0.0'),
                 artifact('org.jetbrains', 'annotations', '13.0'),
                 artifact('junit', 'junit', '4.13.2')]
    libraries += [artifact('org.jetbrains.kotlinx', name, version) for name, version in [
        ('kotlinx-coroutines-core-jvm', '1.10.2-1.0.0'),
        ('kotlinx-serialization-core-jvm', '1.9.1-1.0.0'),
        ('kotlinx-serialization-json-jvm', '1.9.1-1.0.0')]]
    aar = artifact('com.tencent.kuikly-open', 'core-android', '2.29.1-2.0.21-ohos', '.aar')
    common = ROOT / 'src/commonMain/kotlin/com/dgtang/debugtools'
    sources = [common / 'bugreport' / f'{name}.kt' for name in
               ['BugReportModels', 'BugReportMessages', 'BugReportStore', 'BugReportWorkspaceStore']]
    sources += [common / 'shake/ShakeDetector.kt',
                ROOT / 'debug-tools-kuikly/src/commonMain/kotlin/com/dgtang/debugtools/kuikly/DebugToolsModule.kt',
                ROOT / 'src/androidUnitTest/kotlin/com/dgtang/debugtools/kuikly/DebugToolsModuleTest.kt']
    with tempfile.TemporaryDirectory(prefix='debug-tools-bridge-') as directory:
        directory = Path(directory)
        core = directory / 'kuikly-core.jar'
        with zipfile.ZipFile(aar) as archive:
            core.write_bytes(archive.read('classes.jar'))
        runner = directory / 'FixtureMain.kt'
        runner.write_text('package com.dgtang.debugtools.kuikly\nfun main() { DebugToolsModuleTest().senderContracts() }\n')
        sources.append(runner)
        classpath = os.pathsep.join(map(str, libraries + [core]))
        output = directory / 'regression.jar'
        subprocess.run(['java', '-cp', os.pathsep.join(map(str, compiler)) + os.pathsep + classpath,
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', classpath, '-d', str(output), *map(str, sources)], check=True)
        subprocess.run(['java', '-cp', str(output) + os.pathsep + classpath, 'com.dgtang.debugtools.kuikly.FixtureMainKt'], check=True)


if __name__ == '__main__':
    main()
