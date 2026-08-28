#!/usr/bin/env bash
# Enforces s2s-agent's dependency direction: the generic harness must not
# import a concrete provider or Android API. Fails (exit 1) and prints every
# offending import if the rule is violated. Grep-based, not a Gradle plugin —
# the point is catching a leaked import, not aesthetic packaging.
set -euo pipefail

SRC="core/src/main/java/com/s2s/agent"
FAIL=0

check() {
  local pattern="$1"
  local label="$2"
  local hits
  hits=$(grep -rn --include="*.kt" -E "$pattern" "$SRC" || true)
  if [ -n "$hits" ]; then
    echo "BOUNDARY VIOLATION: s2s-agent imports $label"
    echo "$hits"
    FAIL=1
  fi
}

check '^import android\.' "an Android API"
check '^import com\.s2s\.llm\.(llamacpp|remote|onnx|litert)' "a concrete LanguageModel provider"
check '^import com\.s2s\.context\.local' "a concrete ContextEngine provider"
check '^import com\.s2s\.tools\.' "a concrete Tools provider"
check '^import com\.s2s\.host\.' "s2s-host (composition belongs to the app, not the harness)"

if [ "$FAIL" -ne 0 ]; then
  echo "s2s-agent must depend only on interfaces (LanguageModel, ContextEngine, Tools from speech-to-speech-mobile) — never a concrete implementation."
  exit 1
fi

echo "Boundary check passed: no concrete provider or Android imports in s2s-agent."
