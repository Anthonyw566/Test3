#!/usr/bin/env bash
# One-command verification for Distant Frontiers.
#
#   ./run-tests.sh            run everything that can run here
#   ./run-tests.sh --rerun    force tests to re-execute even if up to date
#
# The core logic tests (ring math, heat/economy rules, config parsing and
# validation, modifier pairing, warp weights) need only Maven Central.
# The full mod compile additionally needs the NeoForge/Mojang hosts and is
# attempted only when they are reachable.
set -uo pipefail
cd "$(dirname "$0")"

EXTRA=()
if [[ "${1:-}" == "--rerun" ]]; then
  EXTRA+=(--rerun-tasks)
fi

echo "══════════════════════════════════════════════════"
echo " Core logic tests (no Minecraft required)"
echo "══════════════════════════════════════════════════"
if ! gradle :core:test --console=plain "${EXTRA[@]}" 2>&1 | grep -E "PASSED|FAILED|BUILD|error:"; then
  echo "CORE TESTS FAILED — full output above."
  exit 1
fi

python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
t = f = s = 0
for p in glob.glob('core/build/test-results/test/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests')); f += int(r.get('failures')) + int(r.get('errors')); s += int(r.get('skipped'))
print(f"\nRESULT: {t} tests, {f} failed, {s} skipped")
raise SystemExit(1 if f else 0)
EOF
CORE_OK=$?

echo
echo "══════════════════════════════════════════════════"
echo " Full mod compile (needs NeoForge/Mojang hosts)"
echo "══════════════════════════════════════════════════"
if curl -s -o /dev/null --max-time 8 https://maven.neoforged.net/ 2>/dev/null; then
  gradle compileJava --console=plain 2>&1 | tail -20
else
  echo "SKIPPED: maven.neoforged.net is not reachable from this network."
  echo "Allow these hosts in the environment network policy to enable:"
  echo "  maven.neoforged.net, libraries.minecraft.net,"
  echo "  piston-meta.mojang.com, piston-data.mojang.com"
fi

exit $CORE_OK
