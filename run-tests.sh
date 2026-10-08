#!/usr/bin/env bash
# Runs the core rule tests (no Minecraft needed) and prints a one-line result.
# The full mod build + in-game tests run with:
#   ./gradlew build runGameTestServer
# and automatically in CI on every push (.github/workflows/build.yml).
set -uo pipefail
cd "$(dirname "$0")"

GRADLE=./gradlew
if ! curl -s -o /dev/null --max-time 5 https://services.gradle.org/ 2>/dev/null && command -v gradle >/dev/null; then
  GRADLE=gradle
fi

$GRADLE :core:test --console=plain "$@" 2>&1 | grep -E "FAILED|BUILD|error:" || true

python3 - <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
total = failed = 0
for path in glob.glob('core/build/test-results/test/*.xml'):
    root = ET.parse(path).getroot()
    total += int(root.get('tests'))
    failed += int(root.get('failures')) + int(root.get('errors'))
    for case in root.iter('testcase'):
        for problem in list(case.iter('failure')) + list(case.iter('error')):
            print(f"FAIL {case.get('classname').split('.')[-1]}.{case.get('name')}: {(problem.get('message') or '')[:300]}")
print(f"RESULT: {total} core tests, {failed} failed")
sys.exit(1 if failed or total == 0 else 0)
EOF
