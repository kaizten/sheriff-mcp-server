#!/usr/bin/env bash
# A benchmark of the repair loop: which rules Sheriff finds most, which of
# them its own fixers repair and which a model does, what is left, and what
# each repair took in time and tokens, per backend and model.
#
#   scripts/benchmark.sh                          every project, claude_cli and codex_cli, once
#   scripts/benchmark.sh --runs 3                 three runs of each, to see how much they vary
#   scripts/benchmark.sh --backend claude_cli:claude-sonnet-5-5 --backend codex_cli:gpt-5.5
#   scripts/benchmark.sh --project spring-petclinic
#   scripts/benchmark.sh --report DIR             the tables again, from runs already made
#
# Needs Docker and the kaizten/sheriff image, git, java, python3, and the
# assistant of each backend logged in; npm for the TypeScript project. Every
# run starts from a fresh clone at a pinned commit and spends tokens: on
# PetClinic a run is some five passes of the model.
#
# Each run leaves, under the output folder (by default
# ${XDG_STATE_HOME:-~/.local/state}/sheriff-mcp/benchmark/<when>):
#   <project>/<backend>/<run>/agent.log       what the loop printed
#   <project>/<backend>/<run>/report.json     its --json-report
#   <project>/<backend>/<run>/logs/           the prompts, and metrics-*.jsonl, a line per pass
#   <project>/<backend>/<run>/final/          Sheriff's JSON of an analysis after the run
# and, for all of them, passes.csv, rules.csv, backends.csv and summary.md.
#
# A backend is AI_BACKEND, optionally with a model after a colon: for
# claude_cli it goes to Claude Code as ANTHROPIC_MODEL, for codex_cli to
# codex exec as -m. With no model each uses the one its assistant is set to.
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$repo/sheriff-mcp-java/target/sheriff-mcp.jar"
out="${XDG_STATE_HOME:-$HOME/.local/state}/sheriff-mcp/benchmark/$(date +%Y%m%d-%H%M%S)"
runs=1
backends=()
only=""
report_only=""

# name|repository|commit|component inside the clone ("" for the clone itself)|setup
corpus=(
  "spring-petclinic|https://github.com/spring-projects/spring-petclinic|500158f732419217507c7656904b8e6aa1bcc0d6||"
  "node-express-realworld-example-app|https://github.com/gothinkster/node-express-realworld-example-app|30b68e1e881462b2f4164ea09ab4c4f5699c7b0b||npm ci --ignore-scripts --no-audit --no-fund"
  "gs-rest-service|https://github.com/spring-guides/gs-rest-service|3f4cef01152596c19bc4d37939409358812b1421|complete|"
)

usage() { sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while (( $# > 0 )); do
  case "$1" in
    --runs) runs="$2"; shift 2 ;;
    --backend) backends+=("$2"); shift 2 ;;
    --project) only="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --jar) jar="$2"; shift 2 ;;
    --report) report_only="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done
(( ${#backends[@]} > 0 )) || backends=(claude_cli codex_cli)

# The tables, from every run under a folder.
report() {
  python3 -I - "$1" <<'PYTHON'
import csv, json, pathlib, statistics, sys
from collections import defaultdict

root = pathlib.Path(sys.argv[1])

def final_counts(run):
    counts = defaultdict(int)
    path = run / "final" / "sheriff_errors.json"
    if not path.exists():
        return None
    for components in json.loads(path.read_text()).values():
        for profiles in components.values():
            for findings in profiles.values():
                for finding in findings or []:
                    if finding.get("type", "ERROR") == "ERROR":
                        counts[finding.get("referenceCode") or "(no rule)"] += 1
    return counts

passes_rows, run_rows = [], []
rules = defaultdict(lambda: defaultdict(float))
for metrics in sorted(root.glob("*/*/*/logs/metrics-*.jsonl")):
    run = metrics.parent.parent
    project, backend, number = run.parts[-3], run.parts[-2], run.parts[-1]
    report = json.loads((run / "report.json").read_text()) if (run / "report.json").exists() else {}
    elapsed = (run / "elapsed_s").read_text().strip() if (run / "elapsed_s").exists() else ""
    lines = [json.loads(line) for line in metrics.read_text().splitlines() if line.strip()]
    initial = next((p["errors_before"] for p in lines if p["kind"] == "deterministic"), None)
    left = final_counts(run)
    model_tokens = model_out = model_cost = model_ms = model_repaired = sheriff_repaired = introduced = 0
    models = set()
    costed = False
    for p in lines:
        repaired = sum(p["repaired"].values())
        usage = p["usage"] or {}
        tokens = sum(usage.get(k, 0) for k in ("input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens"))
        models.update(usage.get("models", []))
        if p["kind"] == "model":
            model_tokens += tokens
            model_out += usage.get("output_tokens", 0)
            model_cost += usage.get("cost_usd") or 0
            costed = costed or usage.get("cost_usd") is not None
            model_ms += p["elapsed_ms"]
            model_repaired += repaired
            if p["errors_after"] is not None:
                introduced += sum(max(0, n - p["errors_before"].get(rule, 0)) for rule, n in p["errors_after"].items())
        else:
            sheriff_repaired += repaired
        rules_seen = set(p["errors_before"]) | set((p["errors_after"] or {}))
        for rule in sorted(rules_seen):
            got = p["repaired"].get(rule, 0)
            share = got / repaired if repaired else 0
            passes_rows.append([project, backend, number, p["label"], p["kind"], p["elapsed_ms"], rule,
                                p["errors_before"].get(rule, 0),
                                "" if p["errors_after"] is None else p["errors_after"].get(rule, 0), got,
                                round(tokens * share), round(p["elapsed_ms"] * share)])
            key = rules[(project, backend, rule)]
            if p["kind"] == "model":
                key["model_repaired"] += got
                if p["errors_after"] is not None:
                    key["model_introduced"] += max(0, p["errors_after"].get(rule, 0) - p["errors_before"].get(rule, 0))
                key["model_tokens"] += tokens * share
                key["model_ms"] += p["elapsed_ms"] * share
            else:
                key["sheriff_repaired"] += got
    for rule, count in (initial or {}).items():
        rules[(project, backend, rule)]["detected"] += count
    for rule, count in (left or {}).items():
        rules[(project, backend, rule)]["left"] += count
    configured = (run / "model").read_text().strip() if (run / "model").exists() else ""
    if not models and configured:
        models.add(configured)
    run_rows.append([project, backend, number, ",".join(sorted(models)), report.get("ok", ""),
                     report.get("stopped_reason", ""), report.get("tests_passed", ""), elapsed,
                     sum((initial or {}).values()), "" if left is None else sum(left.values()),
                     sheriff_repaired, model_repaired, introduced, len([p for p in lines if p["kind"] == "model"]),
                     round(model_ms / 1000, 1), model_tokens, model_out, round(model_cost, 4) if costed else "",
                     round(model_tokens / model_repaired) if model_repaired else "",
                     round(model_ms / 1000 / model_repaired, 1) if model_repaired else ""])

runs_per_backend = defaultdict(int)
for row in run_rows:
    runs_per_backend[(row[0], row[1])] += 1

with open(root / "passes.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["project", "backend", "run", "pass", "kind", "elapsed_ms", "rule", "before", "after", "repaired",
                "tokens_share", "elapsed_ms_share"])
    w.writerows(passes_rows)
with open(root / "backends.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["project", "backend", "run", "models", "ok", "stopped_reason", "tests_passed", "elapsed_s",
                "errors_detected", "errors_left", "repaired_by_sheriff", "repaired_by_model",
                "introduced_by_model", "model_passes",
                "model_s", "model_tokens", "model_output_tokens", "model_cost_usd", "tokens_per_repair",
                "seconds_per_repair"])
    w.writerows(run_rows)
with open(root / "rules.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["project", "backend", "rule", "detected_per_run", "sheriff_repaired_per_run", "model_repaired_per_run",
                "model_introduced_per_run", "left_per_run", "model_tokens_per_repair", "model_seconds_per_repair"])
    for (project, backend, rule), v in sorted(rules.items(),
                                              key=lambda kv: (kv[0][0], kv[0][1], -kv[1]["detected"], kv[0][2])):
        n = runs_per_backend[(project, backend)] or 1
        w.writerow([project, backend, rule, round(v["detected"] / n, 1), round(v["sheriff_repaired"] / n, 1),
                    round(v["model_repaired"] / n, 1), round(v["model_introduced"] / n, 1), round(v["left"] / n, 1),
                    round(v["model_tokens"] / v["model_repaired"]) if v["model_repaired"] else "",
                    round(v["model_ms"] / 1000 / v["model_repaired"], 1) if v["model_repaired"] else ""])

def table(path, columns, limit=None):
    rows = list(csv.DictReader(open(path)))[:limit]
    head = "| " + " | ".join(columns) + " |\n|" + "---|" * len(columns) + "\n"
    return head + "".join("| " + " | ".join(str(r[c]) for c in columns) + " |\n" for r in rows)

summary = ["# Benchmark of the repair loop\n", f"{len(run_rows)} run(s) under `{root}`.\n",
           "## By backend\n", table(root / "backends.csv", ["project", "backend", "run", "models", "stopped_reason",
           "errors_detected", "repaired_by_sheriff", "repaired_by_model", "introduced_by_model", "errors_left",
           "model_passes", "model_s",
           "model_tokens", "model_cost_usd", "tokens_per_repair", "seconds_per_repair"]),
           "\n## By rule (per run of each backend)\n", table(root / "rules.csv", ["project", "backend", "rule",
           "detected_per_run",
           "sheriff_repaired_per_run", "model_repaired_per_run", "model_introduced_per_run", "left_per_run",
           "model_tokens_per_repair",
           "model_seconds_per_repair"]),
           "\nA pass's tokens and time are shared among the rules it repaired in proportion to the errors of each. "
           "What a model's pass repaired is what each rule lost in its files; what it introduced, what each gained. "
           "Cost is Claude Code's list price, also on a subscription, and Codex reports none; Codex names no "
           "model either, so its column is the one asked for or its configuration's. JavaPomOutdatedDependency "
           "asks the network, so what it finds changes with new releases anywhere.\n"]
(root / "summary.md").write_text("\n".join(summary))
print((root / "summary.md").read_text())
PYTHON
}

if [[ -n "$report_only" ]]; then
  report "$report_only"
  exit $?
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
    for (( number = 1; number <= runs; number++ )); do
      run="$out/$name/${backend//[:\/]/_}/$number"
      mkdir -p "$run/logs" "$run/final"
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
      echo "== $name, $backend, run $number"
      started=$(date +%s)
      env TARGET_REPO="$target" SHERIFF_COMPONENT="$module" AI_BACKEND="$ai" PROMPT_LOG_DIR="$run/logs" \
          ${env_model[@]+"${env_model[@]}"} java -jar "$jar" --agent --json-report "$run/report.json" > "$run/agent.log" 2>&1
      echo $(( $(date +%s) - started )) > "$run/elapsed_s"
      env TARGET_REPO="$target" SHERIFF_COMPONENT="$module" SHERIFF_EXPORT_DIR="$run/final" \
          java -jar "$jar" --agent --check-only > "$run/final/check.log" 2>&1
      tail -1 "$run/agent.log"
    done
  done
done

report "$out"
