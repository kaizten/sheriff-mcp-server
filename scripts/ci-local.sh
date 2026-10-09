#!/usr/bin/env bash
# Runs here, before a push, what the workflows run on GitHub, so that a push is
# never the first test of a change: three Windows failures in a row were found
# by CI before this existed. The steps are read out of .github/workflows/ci.yml
# by name and run as written, so there is no second copy to drift.
#
#   scripts/ci-local.sh              everything this machine can run
#   scripts/ci-local.sh --no-build   reuse the jar in sheriff-mcp-java/target
#   scripts/ci-local.sh --e2e        and scripts/e2e.sh (Docker and the image)
#
# Every installer step runs with a HOME, a Java user.home and a CODEX_HOME of
# its own, and a PATH with no claude, codex, agy or gh: one of them ends by
# removing ~/.claude, and with the real ones they would set up this machine.
# PowerShell runs as PowerShell 7 in a container, with a JDK copied out of
# eclipse-temurin once into ~/.cache/sheriff-ci. Needs bash, python3 with
# PyYAML, Java and Maven; Docker for PowerShell, bash 3.2 and e2e; latexmk for
# the playbook. What needs none of those is never skipped.
#
# What only GitHub reaches, and the summary says so: Windows PowerShell 5.1,
# cmd, Git Bash, winget and macOS. A green here is not a green there; it is a
# push that is no longer the first try.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly repo
readonly workflow="$repo/.github/workflows/ci.yml"
readonly pwsh_image="mcr.microsoft.com/powershell:latest"
readonly jdk_image="eclipse-temurin:21-jdk"
readonly cache="${XDG_CACHE_HOME:-$HOME/.cache}/sheriff-ci"
build=1
e2e=0
for argument in "$@"; do
  case "$argument" in
    --no-build) build=0 ;;
    --e2e) e2e=1 ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) echo "ci-local.sh: unknown option '$argument' (try --help)" >&2; exit 2 ;;
  esac
done

work="$(mktemp -d "${TMPDIR:-/tmp}/sheriff-ci.XXXXXX")"
readonly work
cleanup() {
  if [[ "$work" == */sheriff-ci.* ]]; then
    rm -rf -- "$work" 2>/dev/null || true
  fi
}
trap cleanup EXIT
results=()
failures=0

# One check: its name, then the command. Its output goes to a log, shown only
# when it fails.
check() {
  local name="$1" log
  shift
  log="$work/log.$((${#results[@]} + 1))"
  printf '%-78s ' "$name"
  if "$@" >"$log" 2>&1; then
    echo "PASS"
    results+=("PASS  $name")
  else
    echo "FAIL"
    results+=("FAIL  $name")
    failures=$((failures + 1))
    sed 's/^/    | /' "$log" | tail -n 40
  fi
}

skip() {
  printf '%-78s SKIP (%s)\n' "$1" "$2"
  results+=("SKIP  $1: $2")
}

# A step's run block, by its name in ci.yml.
step_script() {
  python3 - "$workflow" "$1" <<'PY'
import sys, yaml
workflow = yaml.safe_load(open(sys.argv[1]))
for job in workflow["jobs"].values():
    for step in job.get("steps", []):
        if step.get("name") == sys.argv[2]:
            print(step["run"])
            sys.exit(0)
sys.exit("no step named " + sys.argv[2])
PY
}

# A PATH of the system's tools without the assistants and gh, through links,
# as /usr/bin cannot be taken off the PATH whole. Java and Maven come along
# from wherever they are.
sandbox_path() {
  local bin="$work/bin" directory entry name
  mkdir -p "$bin"
  for directory in /usr/local/bin /usr/bin /bin "$(dirname "$(command -v java)")" "$(dirname "$(command -v mvn)")"; do
    [[ -d "$directory" ]] || continue
    for entry in "$directory"/*; do
      name="$(basename "$entry")"
      case "$name" in claude|codex|agy|gh) continue ;; esac
      [[ -e "$bin/$name" || -L "$bin/$name" ]] || ln -s "$entry" "$bin/$name"
    done
  done
  echo "$bin"
}

# A bash step of ci.yml, as GitHub runs it (bash -e -o pipefail), from the
# checkout, with a home and a runner folder of its own.
run_bash_step() {
  local name="$1" run="$work/run.$RANDOM" home
  home="$run/home"
  mkdir -p "$home" "$run/temp"
  step_script "$name" > "$run/step.sh"
  case "$home" in "$work"/*) ;; *) echo "refusing to run with HOME=$home"; return 1 ;; esac
  (cd "$repo" && env -i HOME="$home" PATH="$sandbox" LANG=C.UTF-8 TERM=dumb \
    RUNNER_TEMP="$run/temp" RUNNER_OS=Linux CODEX_HOME="$home/.codex" \
    JAVA_TOOL_OPTIONS="-Duser.home=$home" \
    bash --noprofile --norc -e -o pipefail "$run/step.sh")
}

# A JDK that runs in the PowerShell container, copied once out of Temurin's.
container_jdk() {
  if [[ ! -x "$cache/jdk21/bin/java" ]]; then
    mkdir -p "$cache"
    docker pull -q "$jdk_image" >/dev/null
    local id
    id="$(docker create "$jdk_image")"
    docker cp -q "$id:/opt/java/openjdk" "$cache/jdk21"
    docker rm "$id" >/dev/null
  fi
  echo "$cache/jdk21"
}

# A PowerShell script in PowerShell 7, as this user, with the checkout
# read-only at /r (its working directory), the JDK first on the PATH, and a
# home and a runner folder of its own under /w.
run_pwsh() {
  local script="$1" run="$work/pwsh.$RANDOM"
  mkdir -p "$run/home" "$run/temp" "$run/stand"
  cp "$script" "$run/script.ps1"
  docker run --rm --user "$(id -u):$(id -g)" -w /r -v "$repo:/r:ro" -v "$jdk:/opt/jdk:ro" -v "$run:/w" \
    -e HOME=/w/home -e RUNNER_TEMP=/w/temp -e JAVA_TOOL_OPTIONS=-Duser.home=/w/home \
    -e PATH=/w/stand:/opt/jdk/bin:/opt/microsoft/powershell/7:/usr/local/bin:/usr/bin:/bin \
    "$pwsh_image" pwsh -NoProfile -NonInteractive -File /w/script.ps1
}

# A PowerShell step of ci.yml, with what GitHub puts before it.
run_pwsh_step() {
  local script="$work/step.$RANDOM.ps1"
  { echo "\$ErrorActionPreference = 'Stop'"; step_script "$1"; } > "$script"
  run_pwsh "$script"
}

# install.ps1 from the checkout with stand-in assistants, as the Windows step
# does with cmd: every assistant set up, Java by its path, the server checked.
pwsh_assistants() {
  local script="$work/assistants.ps1"
  cat > "$script" <<'PS'
$ErrorActionPreference = 'Stop'
foreach ($tool in 'claude', 'codex', 'agy') {
    Set-Content -Path "/w/stand/$tool" -Value "#!/bin/sh`necho `"$tool `$*`" >> /w/calls"
}
Set-Content -Path '/w/stand/mvn' -Value "#!/bin/sh`nexit 0"
& chmod +x /w/stand/claude /w/stand/codex /w/stand/agy /w/stand/mvn
New-Item -ItemType Directory -Force /w/home/.claude | Out-Null
$env:SHERIFF_HOME = '/w/home/sheriff'
$log = & ./scripts/install.ps1 -NoPull 6>&1 | Out-String
$log
if ($log -notmatch 'Sheriff is set up for Claude Code, Codex and Antigravity\.') { throw 'no summary for three assistants' }
if ($log -notmatch 'The MCP server starts: OK') { throw 'the server was not started once' }
if ((Get-Content -Raw /w/home/.claude/settings.json) -match '"command" : "java ') { throw 'the hooks run java from the PATH' }
if ((Get-Content -Raw /w/calls) -notmatch 'claude mcp add --scope user sheriff -- /opt/jdk/bin/java -jar') { throw 'the server was not registered with the Java found' }
PS
  run_pwsh "$script"
}

pwsh_parses() {
  docker run --rm -v "$repo:/r:ro" "$pwsh_image" pwsh -NoProfile -NonInteractive -c '
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile("/r/scripts/install.ps1", [ref]$null, [ref]$errors) | Out-Null
    if ($errors) { $errors | ForEach-Object { "$($_.Extent.StartLineNumber): $($_.Message)" }; exit 1 }'
}

ascii_only() {
  ! LC_ALL=C grep -nP '[^\x00-\x7F]' "$repo/scripts/install.ps1"
}

workflows_parse() {
  local file
  for file in "$repo"/.github/workflows/*.yml; do
    python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1]))' "$file" || return 1
  done
  if command -v actionlint >/dev/null 2>&1; then
    actionlint "$repo"/.github/workflows/*.yml
  fi
}

shell_syntax() {
  local file
  for file in "$repo"/scripts/*.sh; do
    bash -n "$file" || return 1
  done
}

bash_32() {
  docker run --rm -v "$repo:/r:ro" bash:3.2 bash -c 'bash -n /r/scripts/install.sh && bash /r/scripts/install.sh --help >/dev/null'
}

echo "Local CI in $work"
for tool in python3 java mvn; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ci-local.sh: $tool is needed" >&2
    exit 2
  fi
done
if ! python3 -c 'import yaml' 2>/dev/null; then
  echo "ci-local.sh: python3 needs PyYAML (python-yaml, or pip install pyyaml)" >&2
  exit 2
fi
docker_ok=0
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  docker_ok=1
fi

check "the workflows parse" workflows_parse
check "every script in scripts/ is valid bash" shell_syntax
check "install.ps1 is ASCII only" ascii_only
if (( docker_ok == 1 )); then
  check "install.sh parses and runs --help under bash 3.2 (macOS)" bash_32
  check "install.ps1 parses in PowerShell" pwsh_parses
else
  skip "install.sh under bash 3.2, install.ps1 parse" "no Docker"
fi

if (( build == 1 )); then
  check "Build and test both modules (mvn -B install)" bash -c "cd '$repo' && mvn -B -q install"
elif [[ ! -f "$repo/sheriff-mcp-java/target/sheriff-mcp.jar" ]]; then
  echo "ci-local.sh: --no-build, and there is no sheriff-mcp-java/target/sheriff-mcp.jar" >&2
  exit 2
fi

sandbox="$(sandbox_path)"
check "ci.yml: The installer sets up the assistants there are (bash)" \
  run_bash_step "The installer sets up the assistants there are, and only those (bash)"
if curl -fsSI -o /dev/null https://github.com 2>/dev/null; then
  check "ci.yml: A release installs with no gh (bash)" run_bash_step "A release installs with no gh (bash)"
else
  skip "ci.yml: A release installs with no gh (bash)" "no network"
fi

if (( docker_ok == 1 )); then
  jdk="$(container_jdk)"
  check "install.ps1 sets up the assistants there are (PowerShell 7)" pwsh_assistants
  check "ci.yml: A release installs with no gh (PowerShell 7)" run_pwsh_step "A release installs with no gh (PowerShell 7)"
else
  skip "PowerShell 7 steps" "no Docker"
fi

if command -v latexmk >/dev/null 2>&1; then
  check "playbook.yml: the playbook builds in both languages" "$repo/scripts/playbook.sh"
else
  skip "playbook.yml: the playbook builds" "no latexmk"
fi

if (( e2e == 1 )); then
  check "ci.yml e2e: scripts/e2e.sh" "$repo/scripts/e2e.sh"
fi

echo
printf '%s\n' "${results[@]}"
echo
echo "Only GitHub reaches: Windows PowerShell 5.1, cmd, Git Bash, winget and macOS."
if (( failures > 0 )); then
  echo "$failures failed: fix them before pushing."
  exit 1
fi
echo "Nothing failed here."
