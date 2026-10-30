#!/usr/bin/env bash
# A benchmark of the repair loop: which rules Sheriff finds most, which of
# them its own fixers repair and which a model does, what is left, and what
# each repair took in time and tokens, per backend, model and strategy.
#
#   scripts/benchmark.sh                          every project, claude_cli and codex_cli, both strategies, once
#   scripts/benchmark.sh --runs 3                 three runs of each, to see how much they vary
#   scripts/benchmark.sh --backend claude_cli:claude-sonnet-5-5 --backend codex_cli:gpt-5.5
#   scripts/benchmark.sh --strategy file:5 --strategy rule:5 --strategy file:1 --strategy file:0 --strategy file:0:20
#   scripts/benchmark.sh --project spring-petclinic
#   scripts/benchmark.sh --repo ~/code/shop --repo ~/code/monorepo:api   your own projects instead
#   scripts/benchmark.sh --report DIR             the tables again, from runs already made
#
# Needs Docker and the kaizten/sheriff image, git, java, and the assistant of
# each backend logged in; npm for the TypeScript project. Every run starts
# from a fresh clone at a pinned commit and spends tokens: on PetClinic a run
# is some five passes of the model.
#
# --repo PATH runs your own project instead of the ones below: a git
# repository, cloned at its HEAD for every run, so every run starts from the
# same code and the repository itself is never touched. What is not
# committed is not in the clone, and the run says so. PATH:COMPONENT names a
# module of it; with no component, the repository is the module.
#
# Each run leaves, under the output folder (by default
# ${XDG_STATE_HOME:-~/.local/state}/sheriff-mcp/benchmark/<when>), in
# <project>/<backend>/<strategy>/<run>/:
#   agent.log       what the loop printed
#   report.json     the run's report (--json-report): its configuration, what
#                   it started from and left, every pass, and its totals
#   logs/           the prompts, and metrics-*.jsonl, a line per pass
# and, for all of them, what the jar's --compare-reports makes of the reports:
# summary.md, and configurations.csv, runs.csv, rules.csv and passes.csv.
#
# A backend is AI_BACKEND, optionally with a model after a colon: for
# claude_cli it goes to Claude Code as ANTHROPIC_MODEL, for codex_cli to
# codex exec as -m. With no model each uses the one its assistant is set to.
#
# A strategy is how the loop groups a pass's errors, and how many files it
# takes: file:N is every error of N files, whatever its rule (the loop's
# default, file:5); rule:N is one rule's errors in N files. 0 is no limit:
# file:0 hands the model everything at once, rule:0 a whole rule at a time.
# A third number caps the errors of a pass: file:0:20 is 20 errors a pass,
# whole files while they fit, whatever their number; rule:5:10 is at most 10
# of one rule's errors in at most 5 files.
# Sheriff's own fixers run first in every strategy, so only the model's
# passes differ.
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$repo/sheriff-mcp-java/target/sheriff-mcp.jar"
out="${XDG_STATE_HOME:-$HOME/.local/state}/sheriff-mcp/benchmark/$(date +%Y%m%d-%H%M%S)"
runs=1
backends=()
strategies=()
only=""
report_only=""
repos=()

# name|repository|commit|component inside the clone ("" for the clone itself)|setup
corpus=(
  "spring-petclinic|https://github.com/spring-projects/spring-petclinic|500158f732419217507c7656904b8e6aa1bcc0d6||"
  "node-express-realworld-example-app|https://github.com/gothinkster/node-express-realworld-example-app|30b68e1e881462b2f4164ea09ab4c4f5699c7b0b||npm ci --ignore-scripts --no-audit --no-fund"
  "gs-rest-service|https://github.com/spring-guides/gs-rest-service|3f4cef01152596c19bc4d37939409358812b1421|complete|"
)

usage() { sed -n '2,48p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while (( $# > 0 )); do
  case "$1" in
    --runs) runs="$2"; shift 2 ;;
    --backend) backends+=("$2"); shift 2 ;;
    --strategy) strategies+=("$2"); shift 2 ;;
    --project) only="$2"; shift 2 ;;
    --repo) repos+=("$2"); shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --jar) jar="$2"; shift 2 ;;
    --report) report_only="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done
(( ${#backends[@]} > 0 )) || backends=(claude_cli codex_cli)
(( ${#strategies[@]} > 0 )) || strategies=(file:5 rule:5)
for strategy in "${strategies[@]}"; do
  [[ "$strategy" =~ ^(file|rule):[0-9]+(:[0-9]+)?$ ]] || {
    echo "A strategy is file:N or rule:N, with :E for at most E errors a pass, not $strategy" >&2; exit 2; }
done

# The tables, from every run's report under a folder, by the jar itself.
report() {
  java -jar "$jar" --agent --compare-reports "$1" --csv "$1" > "$1/summary.md"
  cat "$1/summary.md"
}

if [[ -n "$report_only" ]]; then
  report "$report_only"
  exit $?
fi

# Your own projects, in place of the corpus: each at the commit it is at.
if (( ${#repos[@]} > 0 )); then
  corpus=()
  for entry in "${repos[@]}"; do
    path="${entry%%:*}"
    module=""
    [[ "$entry" == *:* ]] && module="${entry#*:}"
    path="$(cd "$path" 2>/dev/null && pwd)" || { echo "No such folder: ${entry%%:*}" >&2; exit 2; }
    commit="$(git -C "$path" rev-parse HEAD 2>/dev/null)" || { echo "$path is not a git repository" >&2; exit 2; }
    if [[ -n "$(git -C "$path" status --porcelain 2>/dev/null)" ]]; then
      echo "$path has changes not committed: the runs start from its last commit, $commit, without them."
    fi
    corpus+=("$(basename "$path")|$path|$commit|$module|")
  done
fi

if [[ ! -f "$jar" ]]; then
  echo "Building the jar..."
  mvn -B -q -f "$repo/pom.xml" install -DskipTests || exit 1
fi
mkdir -p "$out/.mirrors"
echo "Benchmark in $out"

for entry in "${corpus[@]}"; do
  IFS='|' read -r name url commit component setup <<< "$entry"
  [[ -z "$only" || "$only" == "$name" ]] || continue
  mirror="$out/.mirrors/$name.git"
  [[ -d "$mirror" ]] || git clone -q --mirror "$url" "$mirror" || { echo "Cannot clone $url" >&2; continue; }
  for backend in "${backends[@]}"; do
    ai="${backend%%:*}"
    model=""
    [[ "$backend" == *:* ]] && model="${backend#*:}"
    for strategy in "${strategies[@]}"; do
    for (( number = 1; number <= runs; number++ )); do
      run="$out/$name/${backend//[:\/]/_}/${strategy//:/_}/$number"
      mkdir -p "$run/logs"
      echo "$strategy" > "$run/strategy"
      work="$run/work"
      git clone -q "$mirror" "$work/$name" && git -C "$work/$name" checkout -q "$commit" || continue
      git -C "$work/$name" checkout -q -b benchmark
      if [[ -n "$setup" ]]; then
        (cd "$work/$name" && eval "$setup") > "$run/setup.log" 2>&1 || echo "  setup failed, see $run/setup.log"
      fi
      if [[ -n "$component" ]]; then
        target="$work/$name"; module="$component"
      else
        target="$work"; module="$name"
      fi
      env_model=()
      configured="$model"
      if [[ -z "$configured" && "$ai" == codex_cli ]]; then
        configured=$(sed -n 's/^model *= *"\(.*\)".*/\1/p' "${CODEX_HOME:-$HOME/.codex}/config.toml" 2>/dev/null | head -1)
        configured="${configured:-(codex default)}"
      fi
      [[ -n "$configured" ]] && echo "$configured" > "$run/model"
      case "$ai" in
        claude_cli) [[ -n "$model" ]] && env_model=(ANTHROPIC_MODEL="$model") ;;
        codex_cli) [[ -n "$model" ]] && env_model=(CODEX_COMMAND="codex exec --sandbox workspace-write -m $model") ;;
      esac
      IFS=: read -r grouping files errors <<< "$strategy"
      errors="${errors:-0}"
      echo "== $name, $backend, $strategy, run $number"
      started=$(date +%s)
      env TARGET_REPO="$target" SHERIFF_COMPONENT="$module" AI_BACKEND="$ai" PROMPT_LOG_DIR="$run/logs" \
          ${env_model[@]+"${env_model[@]}"} java -jar "$jar" --agent --json-report "$run/report.json" \
          --batch-by "$grouping" --max-files-per-batch "$files" --max-errors-per-batch "$errors" \
          > "$run/agent.log" 2>&1
      echo $(( $(date +%s) - started )) > "$run/elapsed_s"
      tail -1 "$run/agent.log"
    done
    done
  done
done

report "$out"
