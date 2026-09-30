#!/usr/bin/env python3
"""Publish concise Maven/JUnit failures as GitHub check annotations, without API access."""
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def annotation(level, title, message):
    def escape(text):
        return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    title = escape(title).replace(',', '%2C').replace(':', '%3A')
    # Remove terminal sequences from diagnostics before emitting workflow commands.
    message = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', message)
    print(f'::{level} title={title}::{escape(message[:3000])}')


def main():
    # Windows redirected stdout can use a legacy code page. Check annotations are UTF-8.
    sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
    failures = 0
    for directory in ('target/surefire-reports', 'target/failsafe-reports'):
        for path in sorted(Path(directory).glob('TEST-*.xml')):
            try:
                suite = ET.parse(path).getroot()
            except (ET.ParseError, OSError) as error:
                annotation('warning', 'Unreadable test report', f'{path}: {error}')
                continue
            for test in suite.iter('testcase'):
                for kind in ('failure', 'error'):
                    for error in test.findall(kind):
                        failures += 1
                        title = f"{test.get('classname', 'test')}.{test.get('name', 'unknown')}"
                        message = error.get('message', '') + '\n' + (error.text or '')
                        annotation('error', title, message)
    if failures:
        return
    log = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'pocketgit-build.log'
    if log.exists():
        errors = [line for line in log.read_text(encoding='utf-8', errors='replace').splitlines() if '[ERROR]' in line]
        if errors:
            annotation('error', 'Maven build failure', '\n'.join(errors[:12]))
            return
    annotation('warning', 'Build diagnostics', 'No JUnit failures found; inspect the uploaded Maven build log.')


if __name__ == '__main__':
    main()
