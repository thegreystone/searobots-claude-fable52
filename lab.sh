#!/usr/bin/env bash
# Fast local loop for the observation lab: compiles the sub the way the league does
# (bare javac against searobots-api only), compiles the lab against the engine, and runs it.
#
#   ./lab.sh series --a fable --b claude --games 40 --seed 1
#   ./lab.sh trace  --a fable --b codex --seed 2c --every 10
#
# LAB_OUT        compile into a different directory (A/B comparisons of two source trees)
# LAB_SKIP_BUILD set to 1 to reuse the classes already in LAB_OUT
# LAB_EXTRA_CP   extra class directories or jars (e.g. another sub repo's target/classes)
set -euo pipefail
cd "$(dirname "$0")"

native() { # Git Bash rewrites path lists on the command line, so hand java real Windows paths
	case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) cygpath -m "$1" ;; *) printf '%s' "$1" ;; esac
}
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

M2="${M2_REPO:-$HOME/.m2/repository}"
VER="$(sed -n 's#.*<searobots.version>\(.*\)</searobots.version>.*#\1#p' pom.xml)"
API="$(native "$M2/se/hirt/searobots/searobots-api/$VER/searobots-api-$VER.jar")"
ENGINE="$(native "$M2/se/hirt/searobots/searobots-engine/$VER/searobots-engine-$VER.jar")"
OUT="${LAB_OUT:-target/lab}"
mkdir -p "$OUT"
MAIN="$(native "$PWD/$OUT/main")"
TEST="$(native "$PWD/$OUT/test")"

if [ "${LAB_SKIP_BUILD:-0}" != "1" ]; then
	rm -rf "$OUT/main" "$OUT/test"
	mkdir -p "$OUT/main" "$OUT/test"
	find src/main/java -name '*.java' > "$OUT/main.sources"
	find src/test/java -name '*.java' ! -name '*Test.java' > "$OUT/test.sources"
	printf -- '--release 25 -proc:none -cp "%s" -d "%s"\n' "$API" "$MAIN" > "$OUT/main.args"
	printf -- '--release 25 -proc:none -cp "%s" -d "%s"\n' "$API$SEP$ENGINE$SEP$MAIN" "$TEST" > "$OUT/test.args"
	javac "@$OUT/main.args" "@$OUT/main.sources"
	javac "@$OUT/test.args" "@$OUT/test.sources"
fi

printf -- '-cp "%s"\n' "$MAIN$SEP$TEST$SEP$API$SEP$ENGINE${LAB_EXTRA_CP:+$SEP$LAB_EXTRA_CP}" > "$OUT/run.args"
exec java -XX:+UseParallelGC -Xmx4g "@$OUT/run.args" se.hirt.searobots.sub.fable52.lab.Lab "$@"
