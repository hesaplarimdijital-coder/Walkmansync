#!/usr/bin/env bash
# Offline doğrulama: :core modülünü (saf Kotlin, Android bağımlılığı yok) Gradle'ın
# gömülü Kotlin derleyicisiyle derler ve JUnit4 testlerini çalıştırır.
# Kullanım: tools/verify-core.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
L=/opt/gradle/lib
OUT="$(mktemp -d "${TMPDIR:-/tmp}/walkmansync-verify.XXXXXX")"
mkdir -p "$OUT/main" "$OUT/test"

COMPILER_CP="$(ls $L/kotlin-compiler-embeddable-*.jar $L/kotlin-stdlib-2*.jar $L/kotlin-daemon-embeddable-*.jar \
  $L/kotlin-reflect-*.jar $L/annotations-*.jar $L/trove4j-*.jar $L/kotlin-script-runtime-*.jar $L/kotlinx-coroutines-core-jvm-*.jar | tr '\n' ':')"
STDLIB="$(ls $L/kotlin-stdlib-2*.jar)"
COROUTINES="$(ls $L/kotlinx-coroutines-core-jvm-*.jar)"
JUNIT="$(ls $L/junit-4*.jar):$(ls $L/hamcrest-core-*.jar 2>/dev/null || ls $L/hamcrest-*.jar | head -1)"

# Derleyici çıkış kodu korunur; grep çıktı bulamayınca (temiz derleme) pipefail betiği öldürmesin.
kc() {
  local rc=0
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 "$@" >"$OUT/kc.log" 2>&1 || rc=$?
  grep -v JAVA_TOOL_OPTIONS "$OUT/kc.log" || true
  return "$rc"
}

MAIN_SRC=$(find "$ROOT/core/src/main/kotlin" -name '*.kt')
TEST_SRC=$(find "$ROOT/core/src/test/kotlin" -name '*.kt' 2>/dev/null || true)

echo "== :core main derleniyor ($(echo "$MAIN_SRC" | wc -l) dosya)"
kc -cp "$STDLIB:$COROUTINES" -d "$OUT/main" $MAIN_SRC

if [ -n "$TEST_SRC" ]; then
  echo "== :core test derleniyor ($(echo "$TEST_SRC" | wc -l) dosya)"
  kc -cp "$STDLIB:$COROUTINES:$JUNIT:$OUT/main" -d "$OUT/test" $TEST_SRC
  CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' | sed 's#^\./##; s#\.class$##; s#/#.#g')
  echo "== testler çalışıyor"
  java -cp "$STDLIB:$COROUTINES:$JUNIT:$OUT/main:$OUT/test" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v JAVA_TOOL_OPTIONS
fi
