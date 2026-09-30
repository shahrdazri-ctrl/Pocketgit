#!/bin/sh
# A real CLI demonstration in a fresh directory; existing files are never reused.
set -eu

if [ "$#" -gt 1 ]; then
    printf '%s\n' 'usage: sh examples/demo.sh [PATH_TO_POCKETGIT_JAR]' >&2
    exit 2
fi
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
jar=${1:-"$script_dir/../target/pocketgit.jar"}
if [ ! -f "$jar" ]; then
    printf '%s\n' 'error: build first with mvn clean verify, or supply the packaged JAR path' >&2
    exit 1
fi
jar_dir=$(CDPATH= cd -- "$(dirname -- "$jar")" && pwd)
jar="$jar_dir/$(basename -- "$jar")"
java_command=${JAVA_HOME:+"$JAVA_HOME/bin/"}java
pause_seconds=${POCKETGIT_DEMO_PAUSE:-0}
case "$pause_seconds" in
    ''|*[!0-9.]*|.*|*.*.*) printf '%s\n' 'error: POCKETGIT_DEMO_PAUSE must be a nonnegative number' >&2; exit 2 ;;
esac

# Only this script's child processes receive the explicit public demo identity.
POCKETGIT_AUTHOR_NAME='PocketGit Demo'
POCKETGIT_AUTHOR_EMAIL='demo@example.com'
export POCKETGIT_AUTHOR_NAME POCKETGIT_AUTHOR_EMAIL

demo_dir=$(mktemp -d "${TMPDIR:-/tmp}/pocketgit-demo.XXXXXXXX")
cd "$demo_dir"
pause() { if [ "$pause_seconds" != 0 ]; then sleep "$pause_seconds"; fi; }
run() {
    label=$1
    shift
    printf '\n$ %s\n' "$label"
    "$@"
    pause
}
pg() { "$java_command" -jar "$jar" "$@"; }

printf 'PocketGit — snapshots, branches, and byte-exact restore\n'
run 'pocketgit init' pg init
run 'pocketgit config user.name "PocketGit Demo"' pg config user.name 'PocketGit Demo'
run 'pocketgit config user.email "demo@example.com"' pg config user.email 'demo@example.com'
run "printf 'hello\\n' > hello.txt" sh -c "printf 'hello\n' > hello.txt"
run 'pocketgit status' pg status
run 'pocketgit add .' pg add .
run 'pocketgit commit -m "Initial commit"' pg commit -m 'Initial commit'
initial_commit=$(cat .pocketgit/refs/heads/main)
initial_prefix=$(printf '%.12s' "$initial_commit")
run "printf 'world\\n' >> hello.txt" sh -c "printf 'world\n' >> hello.txt"
run 'pocketgit status' pg status
run 'pocketgit diff' pg diff
run 'pocketgit add .' pg add .
run 'pocketgit diff --staged' pg diff --staged
run 'pocketgit commit -m "Update hello"' pg commit -m 'Update hello'
run 'pocketgit branch experiment' pg branch experiment
run 'pocketgit checkout experiment' pg checkout experiment
run "printf 'branch work\\n' > experiment.txt" sh -c "printf 'branch work\n' > experiment.txt"
run 'pocketgit add .' pg add .
run 'pocketgit commit -m "Experiment"' pg commit -m 'Experiment'
run 'pocketgit branch' pg branch
run 'pocketgit log --oneline' pg log --oneline
run 'pocketgit checkout main' pg checkout main
test ! -e experiment.txt
printf 'hello\nworld\n' | cmp - hello.txt
run 'pocketgit log --oneline' pg log --oneline
run "pocketgit restore --commit $initial_prefix hello.txt" pg restore --commit "$initial_prefix" hello.txt
printf 'hello\n' | cmp - hello.txt
run 'cat hello.txt' cat hello.txt
run 'pocketgit restore hello.txt' pg restore hello.txt
printf 'hello\nworld\n' | cmp - hello.txt
run 'pocketgit status' pg status
run 'pocketgit verify' pg verify
printf '\nDemo completed; repository preserved at %s\n' "$demo_dir"
