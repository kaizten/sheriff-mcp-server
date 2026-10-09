#!/usr/bin/env bash
# End-to-end checks against a real Sheriff, with no model and no tokens.
#
# The unit suites run without Docker, which is why they cannot catch what
# only shows up against the real image and real git: a loop that edits the
# wrong branch, a scope check that sees nothing, a protocol stream with prose
# in it, a container that outlives its timeout. Every scenario here is one of
# those, found by running the tools and fixed since. A stand-in for `claude`
# takes the model's place, so the loop runs end to end for free.
#
#   scripts/e2e.sh        needs Docker, the kaizten/sheriff image, git, java
#
# Builds the jar first if it is missing. Exits non-zero if any check fails.
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mcp_jar="$repo/sheriff-mcp-java/target/sheriff-mcp.jar"
work="$(mktemp -d)"
failures=0
trap 'if (( failures == 0 )); then rm -rf "$work"; fi' EXIT

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }
check() { if eval "$2"; then pass "$1"; else fail "$1"; fi; }

if [[ ! -f "$mcp_jar" ]]; then
  echo "Building the jars..."
  mvn -B -q -f "$repo/pom.xml" install -DskipTests || exit 1
fi

# A component with a handful of findings, one of which Sheriff fixes itself.
component() {
  mkdir -p "$1/src/main/java/demo"
  cat > "$1/src/main/java/demo/Thermometer.java" <<'JAVA'
package demo;

public class Thermometer {

    private final String name;

    public Thermometer(String name) {

        this.name = name;
    }


    public static boolean plausible(double celsius) {
        if (celsius < -90)
            return false;
        return celsius <= 57;
    }

    public String label() {
        return "thermometer " + name;
    }
}
JAVA
}

repository() {
  git -C "$1" init -q
  git -C "$1" config user.email e2e@example.com
  git -C "$1" config user.name e2e
  git -C "$1" add -A
  git -C "$1" commit -q -m seed
}

# The stand-in model: appends a comment to the first file its prompt names,
# or, with STRAY_FILE set, writes a file no finding names.
bin="$work/bin"
mkdir -p "$bin"
cat > "$bin/claude" <<'FAKE'
#!/usr/bin/env bash
if [[ -n "${STRAY_FILE:-}" ]]; then
  echo stray >> "$STRAY_FILE"
else
  file=$(grep -oE '[A-Za-z0-9_./-]+\.java' | head -1)   # the prompt arrives on stdin
  target=$(find . -path "*${file#*/}" -type f | head -1)
  [[ -n "$target" ]] && printf '\n// touched\n' >> "$target"
fi
echo '{"result":"done","is_error":false,"num_turns":1}'
FAKE
chmod +x "$bin/claude"

loop() {
  PATH="$bin:$PATH" SHERIFF_MAX_ITERATIONS=1 VERIFICATION_TEST_CMD=true PROMPT_LOG_DIR="$work/logs" \
    java -jar "$mcp_jar" --agent "$@" 2>&1
}

echo "1. The default loop, on a repository holding the component"
multi="$work/multi"
component "$multi/app"
repository "$multi"
seed=$(git -C "$multi" rev-parse HEAD)
base=$(git -C "$multi" branch --show-current)
TARGET_REPO="$multi" SHERIFF_COMPONENT=app loop >"$work/1.out"
check "it works on a branch of its own" '[[ $(git -C "$multi" branch --show-current) == sheriff-agent/* ]]'
# Not only with a catalog beside the module: the loop finds one in the cache,
# or extracts one, and with none at all it still runs Sheriff's default set.
# This used to be skipped unless the catalog sat in the checkout, which since
# the catalog moved to the cache meant always, CI included.
check "Sheriff's own fixers ran inside it, in a commit of their own" \
  'git -C "$multi" log --format=%s | grep -q "no model involved"'
check "the branch that was checked out is untouched" '[[ $(git -C "$multi" rev-parse "$base") == "$seed" ]]'
check "and nothing was left uncommitted" '[[ -z $(git -C "$multi" status --porcelain) ]]'
check "cut short, it still ran the project's tests on what it left" 'grep -q "on what this run leaves behind" "$work/1.out"'

echo "2. The loop, on a repository that is the module itself"
single="$work/single"
component "$single/app"
repository "$single/app"
TARGET_REPO="$single" SHERIFF_COMPONENT=app loop >"$work/2.out"
check "it works on a branch of its own" '[[ $(git -C "$single/app" branch --show-current) == sheriff-agent/* ]]'
check "and commits there" '(( $(git -C "$single/app" rev-list --count HEAD) >= 2 ))'

echo "3. The scope check, on a repository that is the module itself"
stray="$work/stray"
component "$stray/app"
repository "$stray/app"
STRAY_FILE="$stray/app/NOTES.md" TARGET_REPO="$stray" SHERIFF_COMPONENT=app loop --no-sheriff-fix >"$work/3.out"
check "an edit outside the batch is caught" 'grep -q "outside what Sheriff had flagged" "$work/3.out"'

echo "4. No repository at all"
bare="$work/bare"
component "$bare/app"
TARGET_REPO="$bare" SHERIFF_COMPONENT=app loop --no-sheriff-fix >"$work/4.out"
check "the loop refuses, and says why" 'grep -q "not inside a git repository" "$work/4.out"'

echo "5. sheriff_autofix over MCP, as a task"
mcp="$work/mcp"
component "$mcp/app"
repository "$mcp"
# A client that stays connected: it starts the loop, gets a task id back at
# once, and asks sheriff_task about that id until the task has finished.
cat > "$work/client.py" <<'PY'
import json, subprocess, sys, time
server = subprocess.Popen(sys.argv[1:], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=open(sys.argv[0] + ".err", "w"), text=True)
lines = []
def call(request_id, method, params):
    server.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}) + "\n")
    server.stdin.flush()
    line = server.stdout.readline()
    lines.append(line)
    return json.loads(line)["result"]
def text(result):
    return result["content"][0]["text"]
call(1, "initialize", {"protocolVersion": "2025-06-18"})
started = time.time()
accepted = text(call(2, "tools/call", {"name": "sheriff_autofix",
                                       "arguments": {"component": "app", "max_iterations": "1"}}))
print("ACCEPTED_IN %.1f" % (time.time() - started))
task = accepted.split("Task ")[1].split(" ")[0]
print("TASK", task)
for attempt in range(600):
    state = text(call(3 + attempt, "tools/call", {"name": "sheriff_task", "arguments": {"id": task}}))
    if "State: completed" in state or "State: failed" in state:
        break
    time.sleep(1)
print(state)
server.stdin.close()
rest = server.stdout.read()
server.wait()
print("STRAY_STDOUT", len(rest.strip()))
print("NON_JSON", sum(1 for line in lines if not line.startswith("{")))
PY
env -u ANTHROPIC_API_KEY AI_BACKEND=anthropic_api SHERIFF_REPO="$mcp" XDG_STATE_HOME="$work/state" \
  python3 "$work/client.py" java -jar "$mcp_jar" >"$work/5.out"
task5=$(awk '/^TASK /{print $2}' "$work/5.out")
check "sheriff_autofix answers at once with a task id" \
  '[[ -n "$task5" ]] && awk "/^ACCEPTED_IN/{exit !(\$2 < 5)}" "$work/5.out"'
check "standard output carries nothing but JSON-RPC" \
  'grep -q "^NON_JSON 0$" "$work/5.out" && grep -q "^STRAY_STDOUT 0$" "$work/5.out"'
check "sheriff_task reports it finished, naming the command it ran" \
  'grep -q "State: completed" "$work/5.out" && grep -q "sheriff_autofix component=app" "$work/5.out"'
check "and the result names the branch the repository was left on" \
  'grep -q "now on branch .sheriff-agent/" "$work/5.out"'
check "the task folder holds its record, the findings and the tracked files" \
  '(for f in task.json sheriff_errors.json sheriff_tracked_files.json; do [[ -s "$work/state/sheriff-mcp/tasks/$task5/$f" ]] || exit 1; done)'
check "but not the summary, and the mount is left clean" \
  '[[ ! -e "$work/state/sheriff-mcp/tasks/$task5/sheriff_summary.json" ]] && ! ls "$mcp"/sheriff_*.json >/dev/null 2>&1'

echo "6. The hooks, as --install-hooks wires them"
edit="{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$multi/app/src/main/java/demo/Forecast.java\"}}"
fix="{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$multi/app/src/main/java/demo/Thermometer.java\"}}"
git -C "$multi" checkout -q "$base"
(cd "$multi" && java -jar "$mcp_jar" --install-hooks >/dev/null)
gate=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["hooks"]["PreToolUse"][0]["hooks"][0]["command"])' \
  "$multi/.claude/settings.json")
echo "$fix" | CLAUDE_PROJECT_DIR="$multi" PROMPT_LOG_DIR="$work/logs" sh -c "$gate" >/dev/null 2>&1
fix_allowed=$?
echo "$edit" | CLAUDE_PROJECT_DIR="$multi" PROMPT_LOG_DIR="$work/logs" sh -c "$gate" >/dev/null 2>&1
blocked=$?
echo "$edit" | SHERIFF_AGENT_RUNNING=1 CLAUDE_PROJECT_DIR="$multi" PROMPT_LOG_DIR="$work/logs" \
  sh -c "$gate" >/dev/null 2>&1
allowed=$?
session_edit="{\"session_id\":\"e2e-$(basename "$work")\",${edit#\{}"
echo "$session_edit" | CLAUDE_PROJECT_DIR="$multi" PROMPT_LOG_DIR="$work/logs" sh -c "$gate" >/dev/null 2>&1
warned=$?
echo "$session_edit" | CLAUDE_PROJECT_DIR="$multi" PROMPT_LOG_DIR="$work/logs" sh -c "$gate" >/dev/null 2>&1
then_allowed=$?
(cd "$multi" && java -jar "$mcp_jar" --uninstall-hooks >/dev/null)
check "the gate blocks an edit to a failing component" '(( blocked == 2 ))'
check "but never an edit to a file with errors, which is the fix" '(( fix_allowed == 0 ))'
check "and stands aside for the loop's own assistant" '(( allowed == 0 ))'
check "it blocks a session once, then lets the fixes through" '(( warned == 2 && then_allowed == 0 ))'
check "and --uninstall-hooks takes it out again" '! grep -q hook-gate "$multi/.claude/settings.json"'
rm -rf "$multi/.claude"
java -Duser.home="$work/home" -jar "$mcp_jar" --install-hooks --user >/dev/null
check "--user wires them into the user's own settings, as install.sh does" \
  'grep -q hook-gate "$work/home/.claude/settings.json" && grep -q hook-stop "$work/home/.claude/settings.json"'

echo "7. A run that outlives its timeout"
image="${SHERIFF_IMAGE:-kaizten/sheriff:latest}"
# Sheriff containers still running with a folder mounted: only those a
# scenario started, whatever else on the machine is running Sheriff. Counting
# every container of the image failed whenever a session of the user's was
# analyzing something at the same moment.
containers_on() {
  local folder count=0 id
  folder="$(cd "$1" && pwd -P)"
  for id in $(docker ps -q --filter ancestor="$image"); do
    docker inspect --format '{{range .Mounts}}{{.Source}}{{"\n"}}{{end}}' "$id" 2>/dev/null \
      | grep -qxF "$folder" && count=$((count + 1))
  done
  echo "$count"
}
TARGET_REPO="$repo" SHERIFF_COMPONENT=sheriff-mcp-java SHERIFF_TIMEOUT=1 \
  java -jar "$mcp_jar" --agent --check-only >"$work/7.out" 2>&1
left_on_repo=$(containers_on "$repo")
check "it is reported as a timeout" 'grep -q "did not respond" "$work/7.out"'
check "its container is gone" '(( left_on_repo == 0 ))'
sleep 8
check "and left no state behind, even a few seconds later" \
  '(( $(find "$repo" -maxdepth 1 -name "sheriff_*.json" | wc -l) == 0 ))'

echo "8. The MCP server, alone, in a project it has never seen"
lone="$work/lone"
mkdir -p "$lone/cache"
cp "$mcp_jar" "$lone/"
fresh="$work/fresh"
component "$fresh/weather"
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"sheriff_test","arguments":{}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sheriff_guidelines","arguments":{"query":"javadoc"}}}' \
  | (cd "$fresh/weather" && env -u SHERIFF_REPO -u SHERIFF_RULES_CATALOG XDG_CACHE_HOME="$lone/cache" \
      java -jar "$lone/sheriff-mcp.jar" >"$work/8.out" 2>"$work/8.err")
check "a single-module project is analyzed with no configuration" \
  'grep "\"id\":2" "$work/8.out" | grep -q "weather has [0-9]* error"'
check "the rule catalog is extracted by the server itself" '[[ -f "$lone/cache/sheriff-mcp/rules_catalog.json" ]]'
check "and answers questions about the rules" 'grep "\"id\":3" "$work/8.out" | grep -qi "javadoc"'
check "and tells the client how to use the tools together" \
  'grep "\"id\":1" "$work/8.out" | grep -q "\"instructions\":.*sheriff_guidelines"'

echo "9. A TypeScript component, clean and not"
poly="$work/poly"
mkdir -p "$poly/web/src"
echo 'export const answer = 42;' > "$poly/web/src/answer.ts"
(cd "$poly" && java -jar "$mcp_jar" --check >"$work/9a.out" 2>&1)
clean_exit=$?
printf 'import * as fs from "fs";\nexport class Basket {\n  items: number[] = [];\n}\n' > "$poly/web/src/basket.ts"
(cd "$poly" && java -jar "$mcp_jar" --check >"$work/9b.out" 2>&1)
dirty_exit=$?
check "the profile is worked out from the sources" 'grep -q "under TYPESCRIPT" "$work/9a.out"'
check "a clean component passes, despite the traces Sheriff prints for TypeScript" '(( clean_exit == 0 ))'
check "and one with findings fails the check" '(( dirty_exit == 1 ))'

echo "10. The hooks, for TypeScript and for a single-module repository"
edit_ts="{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$poly/web/src/checkout.ts\"}}"
echo "$edit_ts" | CLAUDE_PROJECT_DIR="$poly" XDG_CACHE_HOME="$lone/cache" \
  java -jar "$mcp_jar" --hook-gate >/dev/null 2>&1
ts_gate=$?
fix_ts="{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$poly/web/src/basket.ts\"}}"
echo "$fix_ts" | CLAUDE_PROJECT_DIR="$poly" XDG_CACHE_HOME="$lone/cache" \
  java -jar "$mcp_jar" --hook-gate >/dev/null 2>&1
ts_fix=$?
check "a TypeScript component with errors is guarded as TypeScript" '(( ts_gate == 2 ))'
check "and the file with them can be fixed" '(( ts_fix == 0 ))'
guarded="$work/guarded"
component "$guarded/app"
repository "$guarded/app"
echo "// touched" >> "$guarded/app/src/main/java/demo/Thermometer.java"
echo '{"stop_hook_active":false}' | CLAUDE_PROJECT_DIR="$guarded/app" \
  XDG_CACHE_HOME="$lone/cache" java -jar "$mcp_jar" --hook-stop >/dev/null 2>&1
stop_exit=$?
check "a turn cannot end leaving errors in a single-module repository" '(( stop_exit == 2 ))'
# Codex names no project and runs a hook in the session's directory, which is
# also the cwd its input carries; the hook goes by the directory it runs in.
(cd "$guarded/app" && echo "{\"stop_hook_active\":false,\"cwd\":\"$guarded/app\"}" \
  | env -u CLAUDE_PROJECT_DIR XDG_CACHE_HOME="$lone/cache" java -jar "$mcp_jar" --hook-stop >/dev/null 2>&1)
codex_stop=$?
check "nor when the assistant names no project, as Codex runs its hooks" '(( codex_stop == 2 ))'
# A Codex session opened inside the module's sources ran the hook there, took
# src/main/java/demo for the project, and let every error through.
echo '<project/>' > "$guarded/app/pom.xml"
(cd "$guarded/app/src/main/java/demo" && echo '{"stop_hook_active":false}' \
  | env -u CLAUDE_PROJECT_DIR XDG_CACHE_HOME="$lone/cache" java -jar "$mcp_jar" --hook-stop >/dev/null 2>&1)
nested_stop=$?
check "nor when that session was opened inside the module's sources" '(( nested_stop == 2 ))'

echo "11. The Stop hook, requiring the tests too"
if command -v npm >/dev/null 2>&1; then
  red="$work/red"
  mkdir -p "$red/web/src"
  echo 'export const answer = 42;' > "$red/web/src/answer.ts"
  echo '{"name":"web","scripts":{"test":"echo one test fails >&2; exit 1"}}' > "$red/web/package.json"
  repository "$red"
  echo 'export const other = 1;' >> "$red/web/src/answer.ts"
  echo '{"stop_hook_active":false}' | CLAUDE_PROJECT_DIR="$red" SHERIFF_STOP_RUNS_TESTS=1 \
    XDG_CACHE_HOME="$lone/cache" java -jar "$mcp_jar" --hook-stop >"$work/11.out" 2>&1
  red_exit=$?
  check "a clean component whose tests fail cannot end the turn" '(( red_exit == 2 ))'
  check "and the reason quotes the test output" 'grep -q "one test fails" "$work/11.out"'
else
  echo "  SKIP no npm to run a failing test with"
fi

echo "12. Two projects in one folder, checked at the same time"
twins="$work/twins"
component "$twins/left"
component "$twins/right"
printf '\n\n// more\n' >> "$twins/right/src/main/java/demo/Thermometer.java"
(cd "$twins/left" && java -jar "$mcp_jar" --check >"$work/12l.out" 2>&1) &
(cd "$twins/right" && java -jar "$mcp_jar" --check >"$work/12r.out" 2>&1) &
wait
left_count=$(grep -oE 'left: [0-9]+ error' "$work/12l.out" | grep -oE '[0-9]+')
right_count=$(grep -oE 'right: [0-9]+ error' "$work/12r.out" | grep -oE '[0-9]+')
check "each reports its own component, not the other's" '[[ -n "$left_count" && -n "$right_count" ]]'
check "and they share no state: the one with more errors says so" '(( right_count > left_count ))'
check "and the shared folder is left clean" '(( $(find "$twins" -maxdepth 1 -name "sheriff_*.json" | wc -l) == 0 ))'

echo "13. A project in a folder whose name has a colon, a comma or a quote"
component "$work/plain/app"
(cd "$work/plain/app" && java -jar "$mcp_jar" --check >"$work/13.out" 2>&1)
expected=$(grep -oE 'app: [0-9]+ error' "$work/13.out")
for folder in 'projects:2026' 'x,y' 'say "hi", ok:1'; do
  component "$work/$folder/app"
  (cd "$work/$folder/app" && java -jar "$mcp_jar" --check >"$work/13.out" 2>&1)
  check "under '$folder', the same errors as anywhere else" '[[ -n "$expected" ]] && grep -q "$expected" "$work/13.out"'
done

echo "14. The profile a project declares, in its pom.xml or in .sheriff.properties"
declared="$work/declared/app"
component "$declared"
(cd "$declared" && java -jar "$mcp_jar" --check >"$work/14a.out" 2>&1)
printf '<project><properties><sheriff.profile>JAVA_HEXAGONAL</sheriff.profile></properties></project>\n' \
  > "$declared/pom.xml"
(cd "$declared" && java -jar "$mcp_jar" --check >"$work/14b.out" 2>&1)
rm "$declared/pom.xml"
echo 'profile=JAVA_DDD' > "$declared/.sheriff.properties"
(cd "$declared" && java -jar "$mcp_jar" --check >"$work/14c.out" 2>&1)
base_count=$(grep -oE 'app: [0-9]+ error' "$work/14a.out" | grep -oE '[0-9]+')
declared_count=$(grep -oE 'app: [0-9]+ error' "$work/14b.out" | grep -oE '[0-9]+')
check "with nothing declared, a Java project gets JAVA" 'grep -q "under JAVA[.:]" "$work/14a.out"'
check "the pom.xml property is run beside JAVA, not instead of it" 'grep -q "under JAVA,JAVA_HEXAGONAL" "$work/14b.out"'
check "so declaring the architecture loses none of the JAVA errors" '[[ -n "$base_count" ]] && (( declared_count >= base_count ))'
check "and the properties file works the same, for a project without Maven" 'grep -q "under JAVA,JAVA_DDD" "$work/14c.out"'

echo "15. A server stopped mid-task, as Codex stops one when its session ends"
stopped="$work/stopped"
component "$stopped/app"
repository "$stopped"
slow="$work/slow-bin"
mkdir -p "$slow"
printf '#!/usr/bin/env bash\ntouch "$CLAUDE_STARTED"\nsleep 60\n' > "$slow/claude"
chmod +x "$slow/claude"
# Codex sends SIGTERM and, about 0.3 s later, SIGKILL. The record of a task cut
# off that way used to say "running" for good, or nothing at all when the kill
# landed while it was being written.
cat > "$work/stopper.py" <<'PY'
import json, os, signal, subprocess, sys, time
server = subprocess.Popen(sys.argv[2:], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, text=True, start_new_session=True)
def call(request_id, method, params):
    server.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}) + "\n")
    server.stdin.flush()
    return json.loads(server.stdout.readline())["result"]
call(1, "initialize", {"protocolVersion": "2025-06-18"})
accepted = call(2, "tools/call", {"name": "sheriff_autofix", "arguments": {"component": "app"}})
task = accepted["content"][0]["text"].split("Task ")[1].split(" ")[0]
# Stopped once the model is at work, not while Sheriff's containers run: a
# container outlives a killed docker client and goes on writing into the mount.
for attempt in range(240):
    if os.path.exists(os.environ["CLAUDE_STARTED"]):
        break
    time.sleep(0.5)
server.send_signal(signal.SIGTERM)
time.sleep(0.3)
os.killpg(server.pid, signal.SIGKILL)
server.wait()
folder = os.path.join(sys.argv[1], "sheriff-mcp", "tasks", task)
print("STATE", json.load(open(os.path.join(folder, "task.json")))["state"])
print("FILES", " ".join(sorted(os.listdir(folder))))
PY
PATH="$slow:$PATH" SHERIFF_REPO="$stopped" XDG_STATE_HOME="$work/state15" CLAUDE_STARTED="$work/15.started" \
  python3 "$work/stopper.py" "$work/state15" java -jar "$mcp_jar" >"$work/15.out" 2>&1
left_on_stopped=$(containers_on "$stopped")
check "the task it was running is recorded as interrupted" 'grep -q "^STATE interrupted$" "$work/15.out"'
check "it was stopped with the model at work, and no Sheriff container is left" \
  '[[ -f "$work/15.started" ]] && (( left_on_stopped == 0 ))'
check "and the record was written whole, with nothing half-written beside it" \
  '! grep -q "partial" "$work/15.out" && grep -q "^FILES .*task.json" "$work/15.out"'

echo "16. The same code gets the same answer and the same repair, whoever asks"
# Compared without the task's id and the notice that a newer Sheriff image is
# published: that notice is about the machine, not the code, and it comes from
# a Docker Hub lookup the server starts in the background, so one answer had it
# and the other not when Kaizten published an image during the run (9 October).
answer() {
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":{}}}" \
    | (cd "$1" && XDG_STATE_HOME="$work/state16" XDG_CACHE_HOME="$lone/cache" java -jar "$mcp_jar" 2>/dev/null) \
    | python3 -c 'import sys,json
for line in sys.stdin:
    m = json.loads(line)
    if m.get("id") == 2:
        print("".join(c.get("text", "") for c in m["result"]["content"]))' \
    | grep -v -e '^Task ' -e '^Note: a newer ' -e '^$'
}
for copy in one two; do
  component "$work/same/$copy/app"
  printf 'package demo;\n\nimport java.util.List;\nimport java.util.ArrayList;\n\npublic class Basket {\n}\n' \
    > "$work/same/$copy/app/src/main/java/demo/Basket.java"
  printf '/**\n * Demo.\n */\npackage demo;\n' > "$work/same/$copy/app/src/main/java/demo/package-info.java"
  answer "$work/same/$copy/app" sheriff_test >"$work/16-test-$copy.out"
  answer "$work/same/$copy/app" sheriff_fix >"$work/16-fix-$copy.out"
done
check "package-info.java is never reported for its name, which Java requires" \
  'grep -q "error(s) under JAVA" "$work/16-test-one.out" && ! grep -q "package-info" "$work/16-test-one.out"'
check "two copies of the same code get the same answers" \
  'cmp -s "$work/16-test-one.out" "$work/16-test-two.out" && cmp -s "$work/16-fix-one.out" "$work/16-fix-two.out"'
check "and end up with the same code" 'diff -r "$work/same/one/app/src" "$work/same/two/app/src" >/dev/null'
check "each answer ends with one next step, and the first is the repair" \
  '[[ "$(grep -v "^$" "$work/16-test-one.out" | tail -1)" == "Next step: call the sheriff_fix tool with component '"'"'app'"'"'."* ]] \
   && [[ "$(grep -v "^$" "$work/16-fix-one.out" | tail -1)" == "Next step:"* ]]'
check "one repair applies every fixer: the imports are sorted and nothing is left to offer it" \
  'grep -A1 "import java.util.ArrayList;" "$work/same/one/app/src/main/java/demo/Basket.java" | grep -q "java.util.List" \
   && ! grep -q "can repair this one" "$work/16-fix-one.out"'

echo "17. The Stop hook checks only what the turn changed"
session="e2e-$$"
printf '{"session_id":"%s"}' "$session" | CLAUDE_PROJECT_DIR="$guarded/app" XDG_CACHE_HOME="$lone/cache" \
  java -jar "$mcp_jar" --hook-turn >"$work/17a.out" 2>&1
turn_exit=$?
printf '{"session_id":"%s","stop_hook_active":false}' "$session" | CLAUDE_PROJECT_DIR="$guarded/app" \
  XDG_CACHE_HOME="$lone/cache" java -jar "$mcp_jar" --hook-stop >/dev/null 2>&1
untouched_stop=$?
echo "// touched again" >> "$guarded/app/src/main/java/demo/Thermometer.java"
printf '{"session_id":"%s","stop_hook_active":false}' "$session" | CLAUDE_PROJECT_DIR="$guarded/app" \
  XDG_CACHE_HOME="$lone/cache" java -jar "$mcp_jar" --hook-stop >"$work/17b.out" 2>&1
touched_stop=$?
check "the turn hook never blocks a prompt, and says nothing" '(( turn_exit == 0 )) && [[ ! -s "$work/17a.out" ]]'
check "a turn that left earlier changes as they were ends, errors and all" '(( untouched_stop == 0 ))'
check "one that changed them cannot end with errors in them" '(( touched_stop == 2 ))'
check "and is told so in a few lines" '(( $(wc -l < "$work/17b.out") <= 14 ))'

echo "17b. The command a hook gives for a session without the MCP's tools works as it stands"
fallback=$(grep -o 'Without the sheriff tools, run `[^`]*`' "$work/17b.out" | sed 's/^Without the sheriff tools, run `//; s/`$//')
bash -c "$fallback" >"$work/17c.out" 2>/dev/null
check "the Stop hook names the repair, with the component" \
  'grep -q "Next step: call the sheriff_fix tool with component '"'"'app'"'"'" "$work/17b.out"'
check "its command for a session without the tools runs, and answers as sheriff_fix does" \
  '[[ -n "$fallback" ]] && grep -q "^Ran every fixer Sheriff has" "$work/17c.out"'
check "and that answer's own step is a command too, for the same component" \
  '[[ "$(grep -v "^$" "$work/17c.out" | tail -1)" == "Next step:"*"--call sheriff_fix component='"'"'app'"'"'"* ]]'

echo "18. A Python project, which Sheriff checks too"
snake="$work/snake/tool"
mkdir -p "$snake/src"
printf 'import os\n\ndef BadName(x=[]):\n    print(x) ; return 1\n' > "$snake/src/Bad_Module.py"
(cd "$snake" && java -jar "$mcp_jar" --check >"$work/18.out" 2>&1)
python_exit=$?
check "its profile is worked out from the sources" 'grep -q "under PYTHON" "$work/18.out"'
check "and its errors fail the check, where it used to say nothing was analyzed" '(( python_exit == 1 ))'

echo "19. A method a turn adds needs a test that calls it"
tested="$work/tested/app"
mkdir -p "$tested/src/main/java/demo" "$tested/src/test/java/demo"
names_head='package demo;

/**
 * Helpers for names.
 */
public final class Names {

    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private Names() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }
'
printf '%s}\n' "$names_head" > "$tested/src/main/java/demo/Names.java"
repository "$tested"
printf '%s\n    /**\n     * Returns a name without the spaces around it.\n     *\n     * @param name the name\n     * @return the name, trimmed\n     */\n    public static String clean(String name) {\n        return name.strip();\n    }\n}\n' \
  "$names_head" > "$tested/src/main/java/demo/Names.java"
echo '{"stop_hook_active":false}' | CLAUDE_PROJECT_DIR="$tested" XDG_CACHE_HOME="$lone/cache" \
  java -jar "$mcp_jar" --hook-stop >"$work/19a.out" 2>&1
untested_exit=$?
cat > "$tested/src/test/java/demo/NamesTests.java" <<'JAVA'
package demo;

/**
 * Tests for names.
 */
public class NamesTests {

    private static final String PADDED = " Ana ";

    /**
     * Cleans a padded name.
     *
     * @return the cleaned name
     */
    public String cleansAPaddedName() {
        return Names.clean(PADDED);
    }
}
JAVA
echo '{"stop_hook_active":false}' | CLAUDE_PROJECT_DIR="$tested" XDG_CACHE_HOME="$lone/cache" \
  java -jar "$mcp_jar" --hook-stop >"$work/19b.out" 2>&1
tested_exit=$?
check "a clean turn that added a method no test calls cannot end" \
  '(( untested_exit == 2 )) && grep -q "Names.java: clean" "$work/19a.out"'
check "and is told to add a test for it" 'grep -q "Next step: add a test" "$work/19a.out"'
check "with a test that calls it, the turn ends" '(( tested_exit == 0 ))'

echo
if (( failures > 0 )); then
  echo "$failures check(s) failed. The repositories and outputs are kept in $work."
  exit 1
fi
echo "All checks passed."
