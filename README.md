# Sheriff tools

Ways of reaching **Sheriff** (`kaizten/sheriff:latest`), Kaizten's code
standards analyzer, which ships as a Docker image with a command line and no
library interface. Written during a curricular internship at Kaizten Analytics
(Máster Universitario en IA, UNIR).

| Module | What it is |
|---|---|
| [`sheriff-mcp-java/`](sheriff-mcp-java/) | One jar: Sheriff as five MCP tools a model calls while it writes code, in any project and with no configuration. The same jar holds the agent that repairs a component unattended (`--agent`), the CI gate (`--check`) and the three Claude Code hooks. |
| [`sheriff-maven-plugin/`](sheriff-maven-plugin/) | The same check as a Maven goal bound to `package`, and `sheriff:fix`, which fails when errors are left. |

The chain is developer, assistant, MCP server, agent, Sheriff.

**For the people who use the tools rather than work on them**, there is a
playbook, in Spanish and English: what to install, what to ask the assistant,
what each answer and each hook means, and what to do when something is off. It
is a PDF attached to every release, with that release's version on its cover:

```bash
curl -fsSLO https://github.com/kaizten/sheriff-mcp-server/releases/latest/download/sheriff-playbook-es.pdf
curl -fsSLO https://github.com/kaizten/sheriff-mcp-server/releases/latest/download/sheriff-playbook-en.pdf
```

Its source is in [`playbook/`](playbook/), and `scripts/playbook.sh` builds it.
This readme stays the reference: every option, variable and reason.

## Install

Java 17 or newer, git and Docker, on Linux, macOS or Windows; no GitHub
account, as the repository is public. One line installs the latest release,
and the same line updates it:

```bash
curl -fsSL https://github.com/kaizten/sheriff-mcp-server/releases/latest/download/install.sh | bash
```

```powershell
# Windows, in PowerShell
& ([scriptblock]::Create((irm https://github.com/kaizten/sheriff-mcp-server/releases/latest/download/install.ps1)))
```

Where there is no curl, `wget -qO- <the same URL> | bash` does the same; the
script itself downloads with either. The PowerShell line is a script block
rather than `irm ... | iex` so that the script runs in a scope of its own,
leaving nothing in your session, and takes options.

It installs the jar in `~/.local/share/sheriff-agent/`, pulls Sheriff's image
(about 4 GB the first time, only what changed after that), and sets up the
assistants the machine already has, never installing one: Claude Code when
`claude` is on the PATH or `~/.claude` exists (its desktop app and IDE
extensions use it), with the hooks for every project and, with `claude` on the
PATH, the MCP server registered at user scope as `sheriff`; Codex and
Antigravity the same way when `codex` or `agy` is on the PATH. It ends saying
which it set up. With none of them it writes no assistant's configuration and
says so: the jar is still the CI gate (`--check`) and the Maven plugin's, and
running the line again once an assistant is installed sets it up. A release
carries no rule catalog: the tools extract it from the image on first use
(about 6 s). From a clone, `scripts/install.sh` (`scripts\install.ps1` on
Windows) does the same after building and testing both modules, which needs
Maven; re-run it after pulling, as a registration pointing at a jar that moved
fails at session start with only "connection closed".

Java need not be on the PATH. The installer looks in `JAVA_HOME`, on the PATH
and where JDKs are installed (`/usr/lib/jvm`, macOS's `java_home`, on Windows
`Eclipse Adoptium`, `Java`, `Microsoft`, `Amazon Corretto`, `Zulu` and
`BellSoft` under Program Files, and `~/.jdks`), takes the first 17 or newer,
and sets every assistant up to run it by that absolute path: Claude Code runs
the hooks in Git Bash on Windows, and an editor started from the desktop can
have another PATH, so a bare `java` that works in the terminal failed there.
With none, `install.ps1` installs Temurin 21 with winget. Run the line again after a Java update that
removes the old one. Last, it starts the server as the assistants will and
sends it `initialize`: `The MCP server starts: OK`, or FAIL with the cause.

Options go after `bash -s --` in the one line (`... | bash -s -- --pull-always`),
after the script block in PowerShell (`& ([scriptblock]::Create(...)) -PullAlways`),
or after the script from a clone:

| Option (PowerShell) | What it does |
|---|---|
| `--pull-always` (`-PullAlways`) | The MCP server pulls a newer Sheriff image each time it starts. Without it a newer image is only reported, once per session, so that new rules do not arrive in the middle of someone's work unasked. It is written into the server's registration (`SHERIFF_PULL=always`), for Claude Code and for Codex, which passes a server none of your shell's variables. Give it on every run: a run without it puts the default back |
| `--install-docker` (`-InstallDocker`) | Installs Docker when it is missing, from Docker itself: `get.docker.com` on Linux (`pacman` on Arch, which Docker does not package for), Docker Desktop's installer from docker.com on macOS and on Windows (there per user, with no administrator and WSL 2, as Docker recommends); and starts it when it is stopped. Docker Desktop asks you to accept its terms the first time it starts, and the installer does not accept them for you: they require a paid subscription in a company of more than 250 people or $10 million a year |
| (`-DockerWsl`) | Windows only: uses the Docker engine installed inside WSL, with no Docker Desktop. It checks `wsl docker info`, pulls the image through WSL and sets `SHERIFF_DOCKER=wsl` for the user, which the server and the hooks read to run every `docker` as `wsl.exe docker`, with the mounted folder written as WSL sees it (`C:\x` as `/mnt/c/x`). `SHERIFF_DOCKER=wsl:<distro>` names a distribution other than the default. Restart Claude Code and terminals afterwards |
| `--no-pull` (`-NoPull`) | Leaves the image alone; the MCP server pulls it when it starts and finds none |
| `--release[=TAG]` (`-Release`, `-Tag TAG`) | From a clone, installs a release (the latest, or that one) instead of building |
| `--no-hooks` (`-NoHooks`) | The MCP server without the hooks |
| `--no-codex` (`-NoCodex`) | Leaves Codex alone |
| `--no-antigravity` (`-NoAntigravity`) | Leaves Antigravity alone |
| `--fail-fast` (`-FailFast`) | The hooks stop Sheriff at the first error |
| `--no-mcp` (`-NoMcp`) | Installs the jar (and pulls the image) without touching any assistant's configuration |

By hand, the two things it sets up for Claude Code are:

```bash
claude mcp add --scope user sheriff -- java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --install-hooks --user
```

For Codex, which needs one review of its own, see [Codex](#codex).

## Adding it to a project, step by step

Nothing is copied into the project you want to check: the tools come from the
jar installed once per machine, and the project only gets optional files
(`.mcp.json`, the hooks, a plugin in its `pom.xml`).

What the project is written in and built with decides only two things:

| The project | What happens |
|---|---|
| Java, TypeScript, Vue, Perl or Python | Analyzed, with the profile the project declares or, if none, the one its sources call for |
| Any other language (Go, C#...) | Nothing to analyze, and the tools say so rather than report a pass |
| Built with Maven, Gradle or an npm `test` script | Its tests run after repairs, and in the fix loop |
| No build tool | Analyzed all the same; the tools say no tests were run, never that they passed |

Maven is needed on this machine only to build the tools from a clone, and the
Maven plugin only suits a Maven project; any other project gates its CI with
`--check` (step 6).

**1. Check the machine**, once. Linux, macOS and Windows all work; the
installer checks the first two itself and says what is missing:

```bash
java -version            # 17 or newer (it need not be on the PATH)
docker info              # Docker running (Docker Desktop on macOS and Windows),
                         # or let the installer set it up: --install-docker
claude --version         # Claude Code, for the MCP tools and the hooks
```

**2. Install the tools**, once per machine, with the line under
[Install](#install), and check them:

```bash
curl -fsSL https://github.com/kaizten/sheriff-mcp-server/releases/latest/download/install.sh | bash
claude mcp list          # sheriff: ... ✔ Connected
codex mcp list           # sheriff ... enabled, when Codex is installed
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --version
```

From a clone instead, `scripts/install.sh` (in PowerShell,
`.\scripts\install.ps1`; if PowerShell refuses to run it, allow it for that
window first: `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`).

Neither installs a rule catalog, and that is expected: the server extracts
its own from the image on first use (about 6 s), into `~/.cache/sheriff-mcp/`.

**3. Open the project and check it.** Start Claude Code in the project's root
and ask *"check the code standards"*. The server works out the component and
the profile by itself. For a project that is itself one module, Sheriff mounts
its parent and leaves nothing behind there. Nothing else is needed on this
machine. To share the setup with a team, commit this as `.mcp.json` in the
project's root instead of relying on each machine's registration (use one or
the other: a user registration and a `.mcp.json` both named `sheriff` make
Claude Code report conflicting scopes):

```json
{ "mcpServers": { "sheriff": { "command": "java",
    "args": ["-jar", "/home/<you>/.local/share/sheriff-agent/sheriff-mcp.jar"] } } }
```

**4. Declare its architecture (optional).** With nothing declared, a Java
project is checked under `JAVA`: style and documentation. If it is built on
a hexagonal or DDD architecture, say so once, in its `pom.xml`:

```xml
<properties>
  <sheriff.profile>JAVA_HEXAGONAL</sheriff.profile>
</properties>
```

or, without Maven, in a `.sheriff.properties` beside its sources
(`profile=TYPESCRIPT_HEXAGONAL`). The tools, the hooks, `--check` and the
Maven plugin all read it, and the architecture's rules are added to the
`JAVA` ones, never put in their place (details under
[Configuration](#configuration)).

**5. The hooks are already on.** `scripts/install.sh` wrote them into
`~/.claude/settings.json`, for every project Claude Code opens: the first
edit to a component that already has errors is blocked once, with the errors
to clear, and a turn cannot end with errors in what it changed. Where there
is nothing Sheriff analyzes they let everything through, in about 0.1 s.
Claude Code normally picks them up by itself, open sessions included. The same
step lets `sheriff_test`, `sheriff_fix`, `sheriff_guidelines` and
`sheriff_task` run without a permission prompt (`permissions.allow`, every
other rule kept): in auto mode they already did, but Haiku stopped at the
first `sheriff_fix`, so the work went differently with each model.
`sheriff_fix` only applies Sheriff's own fixers, which git undoes;
`sheriff_autofix` commits and spends tokens, and still asks.

`--uninstall-hooks --user` takes them out again. To have them in one project
only (a machine installed with `--no-hooks`, or a team that shares them by
committing the file), run this from the project's root, which writes its
`.claude/settings.json` instead:

```bash
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --install-hooks
```

That file names the jar by its path, so commit it only if every machine
installs to the same place. A project that has them too runs them once, as
Claude Code runs a hook defined in both settings files only once; if its copy
names another jar, both run, so take that one out with `--uninstall-hooks`.

**6. Gate it in CI or in the build (optional).** Either the jar, from the
project's root (exit 0 clean, 1 errors, 2 could not check):

```bash
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --check
```

or the Maven plugin, which `scripts/install.sh` already put in `~/.m2`; add it
to the project's `pom.xml` (plugin XML under [Use](#use)) and `mvn package`
fails on errors. For a CI runner, install the plugin there too
(`mvn install` in this repository) and pull the image first.

**If something is off:**

| Symptom | Cause |
|---|---|
| `sheriff: ... ✗ Failed to connect` | The jar moved or was never built, or `java` is older than 17: run the installer again, which checks |
| "... could not be downloaded", while installing | No network, or a proxy curl or PowerShell is not told about; with `--release=TAG` (`-Tag`), no release by that name |
| "Sheriff's image ... is being downloaded" | The first use on this machine: wait a few minutes |
| "... could not be downloaded" | Docker is not running, or there is no network |
| "... no matching manifest for windows" | Docker Desktop is set to Windows containers: switch it to Linux containers (its default) |
| "a newer kaizten/sheriff:latest has been published" | Run the install line again, which pulls it; or install with `--pull-always`, and the server pulls it each time it starts |
| "The analyzer could not run" | Docker stopped after the image was pulled |
| "Nothing was analyzed" | The component holds no sources of that profile's language: pass `component`, or check the profile the project declares |
| "No rule catalog is available" | No Docker when the server first started: start Docker, and the next rule lookup tries again (at most a minute later) |
| The hooks do nothing | The session did not pick up the change to its settings: restart it, and check them with `/hooks` |
| `package-info.java` fails `FilenamePascalCase` | A false positive of Sheriff's: Java fixes that file name. Leave it |

Tried from scratch on 28 September: a fresh clone of this repository (no
catalog) and a fresh clone of Spring PetClinic. Every step worked as written:
Claude Code found the 221 errors on "check the code standards", the gate
blocked the first edit and the Stop hook caught the error it introduced,
`--check` exited 1, and `mvn verify` failed naming the 221.

## Use

Open any Java, TypeScript, Vue, Perl or Python project and ask in plain words:
*"check the code standards"*, *"fix what Sheriff can fix by itself"*, *"what
are the rules for javadoc?"*. When it connects, the server hands the model the
order a change goes in (`src/main/resources/instructions.md`): check first
and clear what was already broken (Sheriff's repairs, then by hand) without
asking, unless the request itself says to leave it; read the rules, write,
check again, run the tests, and check once more at the end, with that result
in the answer.

| Tool | What it does | Spends tokens |
|---|---|---|
| `sheriff_test` | Analyzes a component: how many errors each file and each rule has, and the next step | no |
| `sheriff_fix` | With no arguments, every repair Sheriff has for what it finds, round after round while the count drops; reports errors before and after, and with `verify` runs the tests | no |
| `sheriff_guidelines` | The rules, before anything is broken, with the code Sheriff accepts | no |
| `sheriff_autofix` | The agent's whole repair loop, on its own branch. Answers at once with a task id and runs in the background | yes |
| `sheriff_task` | A task's command, state and result; without an id, the session's tasks | no |

**Every answer of `sheriff_test` and `sheriff_fix` ends with one `Next step:`**,
its last line, worked out from the state alone. After an analysis with errors
it is always `sheriff_fix` with no arguments; after a repair, what is left by
hand, then `sheriff_fix` again, which checks and repairs what the edits broke;
once clean, the project's tests, by a command that runs from anywhere; and
last, that the component is done. The answers were already the same for the
same code; what differed between models was the choice they left (Sonnet
asked, or stopped, where Opus went on), so the answer now makes it, and the
server's instructions are to do that step. Answers that cannot check anything
end with a step too.

What is left by hand comes ready for a model that cannot guess: each file's
errors from its last line up, as an edit moves every line below it, and, once
per family of rules, the Java that Sheriff accepts for it (`accepted-code.md`,
every shape in it checked against the image).

**The answers are as short as the work allows**, measured on PetClinic:
`sheriff_test` gives counts per file and per rule (1.7 KB, was 16 KB), since
its step is `sheriff_fix`, which lists the errors of the first whole files
that hold about twenty, counts the rest per file, and makes those files the
step, writing the folder they share once and a rule's advice once (6 KB, was
18 KB). A Codex session fixed one file per call and got the whole list each
time; the same list asked for again with nothing fixed is said to be the same,
and gets the same files. A component given as a path inside one is answered with the
component to call instead. The answers name the task's id, not its folder:
told where the folder was, a Codex session read Sheriff's raw JSON there
rather than do the step; `sheriff_task` gives it.

**New code is tested, not only clean.** Once a component has no errors, a
method a change added (not private, not in the last commit) that no changed
test calls is named, and the step is a test for it, edge cases included: the
MCP's answers say so, and the Stop hook does not let the turn end until one
calls it. Asked for `Pet.getLastVisitDate()`, two models of three had written
one that throws on a visit with no date, which neither Sheriff nor the 81
tests noticed. Java only, and only where git can tell what changed.

The three that only read say so to the client (MCP's `readOnlyHint`), and the
two that repair say how they write: `sheriff_fix` edits files in place,
`sheriff_autofix` commits on a branch of its own and calls a model. A client
can use that to approve the readers without asking.

Every call that runs Sheriff is a task, with a folder under
`~/.local/state/sheriff-mcp/tasks/<id>/` holding `task.json` and the JSON
Sheriff wrote. `task.json` also records what the task ran with: the jar's
version and Sheriff's image with the digest it was pulled by, so a result can
be traced to the rules that produced it. The newest 50 are kept.

**The standards are enforced, not only offered**, by the hooks
`scripts/install.sh` wires for every project (step 5 above says how to leave
them out, or to have them in one project only). The gate blocks the first edit in a session to a component that already has
errors, and says how to clear them; the edits after it go through. An edit to
a file that has errors is never blocked, as it is the fix. The Stop
hook does not let a turn end with errors left in a component the turn
changed, the ones already there included (and, with
`SHERIFF_STOP_RUNS_TESTS=1`, with failing tests). Which components the turn
changed is the third hook's job: when a prompt arrives it records what the
components git already reports as changed look like, and the Stop hook leaves
alone those still the same, so a question asked in a repository with earlier
work in it is not sent back to fix code. That decision is the hooks', not the
model's. A hook has already analyzed, so its message ends with the step an
analysis by the MCP ends with, worded the same: call the `sheriff_fix` tool
with the component named, follow each answer's step until one ends "Next
step: none, the component is done", and do not ask. Under it, the command
that does the same in a session with no sheriff tools (the server not
registered, or not found by Codex's tool search); with failing tests, the
command that runs them. All fail open: without Java or Docker the work goes
on, with one line on stderr.

**Without an MCP client**, any tool can be called from the project's folder,
and answers exactly as the server does, its steps written as commands:

```bash
java -jar sheriff-mcp.jar --call sheriff_fix [component=api] [profile=JAVA_HEXAGONAL]
```

**In CI**, from the project's root. Exit 0 clean, 1 errors, 2 the check could
not run or found nothing of the profile's language to analyze:

```bash
java -jar sheriff-mcp.jar --check [--component api] [--profile JAVA_HEXAGONAL]
```

**In Maven**, with no configuration: the module's parent is mounted and its
directory name is the component.

```xml
<plugin>
  <groupId>com.kaizten</groupId>
  <artifactId>sheriff-maven-plugin</artifactId>
  <version>1.0.2</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

`sheriff:check` runs in `package`, after the tests, so `mvn package`, `mvn verify`
and `mvn install` all fail on errors; an execution with its own `<phase>`
moves it. `mvn sheriff:fix` is bound
to no phase, because it rewrites source; it analyzes again after repairing and
fails if errors remain, one module at a time even in a parallel build. Both
leave Sheriff's JSON in `target/sheriff/` and skip modules with no `src/`.
Parameters: `sheriff.profile` (the project's declaration, else `JAVA`), `sheriff.image`, `sheriff.timeout`
(`300`), `sheriff.failOnError` (`true`), `sheriff.failOnRemaining` (`true`),
`sheriff.skip`.

## Codex

The same jar works from Codex, and `scripts/install.sh` sets it up for every
project when `codex` is on the PATH, as it does Claude Code. Tested on
`codex-cli` 0.157, in `codex exec` and in the interactive session. What is
left for you is one review: Codex asks before it runs a new hook, the first
time a session starts ("Hooks need review"), or with `/hooks`. Approve it once;
until then the Stop hook does not run, and that hook is what holds Codex to the
work: on PetClinic (`codex-cli` 0.160, 9 October), asked for one method, it
twice called the work done with 133 and 120 errors left, and only the Stop
hook sent it on to 0. The installer's output says so.

```bash
scripts/install.sh               # Claude Code and Codex
scripts/install.sh --no-codex    # Claude Code only
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --install-codex     # Codex alone
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --uninstall-codex   # and back
```

It writes three things into `~/.codex/` (or `$CODEX_HOME`), each replacing an
earlier copy of itself and leaving the rest of the file as it was:

| File | What | Why it is needed |
|---|---|---|
| `config.toml` | The server, `[mcp_servers.sheriff]`, with `tool_timeout_sec = 600` and `sheriff_fix` approved | `codex mcp add` gives neither. Codex cuts a call at 60 s ("timed out awaiting tools/call"), and a call can wait up to 120 s for the rule catalog to be extracted, or run the project's tests with `verify`. `codex exec` refuses a tool that writes unless the entry approves it ("approval policy is never"); an interactive session asks before `sheriff_fix` and `sheriff_autofix` and runs the three read-only tools without asking |
| `AGENTS.md` | The order of work, between two markers | Codex reads this file in every project. It shows a server's MCP instructions only in the description of the tool that finds tools, and asked for a feature without them it called no Sheriff tool at all (three runs out of three); with them, it cleared the errors already there without asking |
| `hooks.json` | The Stop hook, and the turn hook it needs to tell what the turn changed | A turn that leaves errors in what it changed does not end, as under Claude Code, and it blocks again while the model keeps changing files (Codex sends the session id and `stop_hook_active`, measured). The gate does not go in: Codex edits with `apply_patch`, whose call carries a patch rather than a file path; the Stop hook catches the same errors after the edit, by asking git |

`--fail-fast` asks the hooks to stop Sheriff at the first error,
`--no-hooks` leaves `hooks.json` alone, and `--pull-always` writes
`env = { SHERIFF_PULL = "always" }` into the server's entry; `install.sh`
passes all three on. The
hooks take the option on their command line (`--hook-stop --fail-fast`) rather
than as a variable, because Codex starts its hooks and servers with a handful
of variables (`HOME`, `PATH`, `USER`, `LANG` and little more): a variable from
[Configuration](#configuration) set in your shell never reaches them. For the
server, put it in the entry, as `env = { NAME = "value" }`, or pass it
through with `env_vars = ["NAME"]`.

**Start Codex in the project.** Codex does not say where the project is (Claude
Code does, with `CLAUDE_PROJECT_DIR`), so the server and the Stop hook take
it from the directory Codex runs in. A directory inside a module's `src/`
counts as that module, when the module has a build file (`pom.xml`, a Gradle
build, `package.json`) or a `.sheriff.properties`; any other one is taken as a
project of its own.

**`codex exec` runs read-only unless told otherwise.** Its sandbox refuses
every edit ("writing is blocked by read-only sandbox"), and Sheriff's own
tools still run, since the server is outside the sandbox. To let it write:

```bash
codex exec -s workspace-write "Add a method size() to Basket"
```

A hook that was never approved in an interactive session also needs
`--dangerously-bypass-hook-trust` there, since `exec` cannot ask.

**`sheriff_autofix` ends with the session.** Codex stops its servers when a
session ends (SIGTERM to the whole process group, SIGKILL about 0.3 s later),
and the repair loop stops with the server. Its task ends as `interrupted`,
and the repository may be left on the loop's branch with a pass uncommitted,
to review before anything else. Sheriff's `sheriff_*.json` files may be left
at the root of what it mounts too; the next run clears them by itself. In an interactive session the loop runs on between turns, and
`sheriff_task` reports on it, for as long as the session stays open; quitting
cuts it the same way. `codex exec` ends its session as soon as the model
answers, so from `exec` the loop never gets anywhere: run it from the agent's
command line instead.

**The fix loop** can be driven by Codex too, with `AI_BACKEND=codex_cli`, from
the module's folder:

```bash
AI_BACKEND=codex_cli java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --agent
```

Each pass runs `codex exec --sandbox workspace-write`, which records the
folder as trusted in `~/.codex/config.toml`. Google Antigravity's command line
drives it the same way, with `AI_BACKEND=antigravity_cli` (see
[Configuration](#configuration)).

## Antigravity

Google Antigravity's command line, `agy`, is an MCP client too, and the same
server works from it. `scripts/install.sh` sets it up when `agy` is on the PATH
(`--no-antigravity` leaves it alone); by hand:

```bash
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --install-antigravity     # and --uninstall-antigravity
```

That writes two things, each replacing an earlier copy of itself: the server in
`~/.gemini/config/mcp_config.json`, the entry `agy mcp add sheriff -- java -jar
<jar>` writes (with `env` for `--pull-always`), and `mcp(sheriff/sheriff_test)`
and the other three tools that neither commit nor spend tokens in
`permissions.allow` of `~/.gemini/antigravity-cli/settings.json`. `agy` asks
before every tool, and headless mode (the prompt on stdin) refuses whatever it
cannot ask about, MCP tools included; with those rules a headless `agy` ran
`sheriff_test` (7 October). It reads the server's instructions (it caches them,
with the tools, under `~/.gemini/antigravity-cli/mcp/sheriff/`). There are no
hooks. Tried on 7 October with `agy` 1.3.1 on a
small Maven project: asked to fix its standards, it called `sheriff_test` and
`sheriff_fix`, fixed the rest by hand, checked again and ran `mvn test`, ending
at 0 errors. It is also a backend of the fix loop, `AI_BACKEND=antigravity_cli`
(see [Configuration](#configuration)).

## The agent's command line

```bash
java -jar sheriff-mcp.jar --agent [mode]      # from the module, or the project holding it
```

It works out what to mount and which component to repair from where it is
run, as the server does. `TARGET_REPO` (the directory that holds the module)
and `SHERIFF_COMPONENT` (the module's folder) override that; with
`TARGET_REPO` set and no component, it analyzes `sheriff-mcp-java`, which is
how it checks itself.

| Mode | What it does |
|---|---|
| *(none)* | The repair loop: Sheriff's own fixers first, then the model one bounded batch of files at a time, a scope check, a commit per pass. Runs the project's tests however it ends |
| `--check-only` | One Sheriff run, no model, no git |
| `--sheriff-fix` | Only Sheriff's own fixers |
| `--rules [QUERY]` | The rules a profile enforces, or a search |
| `--fixer CODE` | The script Sheriff runs to repair `CODE` |
| `--extract-rules` | Rebuilds the rule catalog from the image |

Loop flags: `--max-iterations N`, `--max-files-per-batch N` (5),
`--no-git-safety` (no branch, no commits, no scope check), `--json-report PATH`.
With no `--max-iterations`, the cap is sized from the first analysis (one pass
per five files with errors, and two to spare) and grows after every pass that
lowers the errors, to what the files still with errors need, up to three times
that first estimate: a project analyzed for the first time, with errors in
hundreds of files and many of them needing a second pass, used to run out of
the two spare passes with work left. A pass that lowers nothing parks its files
instead, and with nothing left to try the run stops.
A pass that touches a file outside its batch stops the run without committing,
and the work is left in the tree for review.

## Configuration

Nothing is required: the server works out the mount, the components, each
one's profile and the test command from the project it is started in.

**A project built on an architecture says so once**, and every tool (the MCP
server, the hooks, `--check` and the Maven plugin) reads it:

```xml
<!-- pom.xml, inherited by the modules below it like any property -->
<properties>
  <sheriff.profile>JAVA_HEXAGONAL</sheriff.profile>
</properties>
```

or, in a project that does not build with Maven, a `.sheriff.properties` file
beside its sources with `profile=TYPESCRIPT_HEXAGONAL`. An architecture
profile is always run **beside** its language's base profile, never instead
of it: `JAVA_HEXAGONAL` does not include the `JAVA` rules, so on its own it
would pass infrastructure code with no JavaDoc and no braces. Several can be
listed (`JAVA_HEXAGONAL,JAVA_DDD`); Sheriff runs one per analysis, so each
listed profile costs one more run. With nothing declared, the base profile of
the language the sources are in.

**Every profile Sheriff accepts**, as its image of 8 October 2026 lists them
(the base profile in bold). Any of them is declared the same way, and gets its
language's base profile added: declaring `JAVA_HEXAGONAL_REST` runs `JAVA` and
`JAVA_HEXAGONAL_REST`.

| Language | Profiles |
|---|---|
| Java | **`JAVA`**, `JAVA_HEXAGONAL`, `JAVA_HEXAGONAL_DOMAIN`, `JAVA_HEXAGONAL_APPLICATION`, `JAVA_HEXAGONAL_REST`, `JAVA_HEXAGONAL_MONGODB`, `JAVA_HEXAGONAL_TIMESCALE`, `JAVA_DDD`, `JAVA_DDD_ENTITY`, `JAVA_DDD_VALUE_OBJECT`, `JAVA_DDD_ENUMERATE`, `JAVA_ENTITY`, `JAVA_VALUE_OBJECT`, `JAVA_ENUMERATE`, `JAVA_USE_CASE`, `JAVA_POM`, `JAVA_COMPILATION` |
| TypeScript | **`TYPESCRIPT`**, `TYPESCRIPT_HEXAGONAL`, `TYPESCRIPT_HEXAGONAL_DOMAIN`, `TYPESCRIPT_HEXAGONAL_APPLICATION`, `TYPESCRIPT_HEXAGONAL_HTTP`, `TYPESCRIPT_HEXAGONAL_VUEJS`, `TYPESCRIPT_ENTITY`, `TYPESCRIPT_VALUE_OBJECT`, `TYPESCRIPT_ENUMERATE`, `TYPESCRIPT_FILENAME`, `TYPESCRIPT_LOCALE`, `TYPESCRIPT_PACKAGE`, `TYPESCRIPT_COMPILATION` |
| Vue | **`VUEJS`**, `VUEJS_COMPONENT`, `VUEJS_VIEW`, `VUEJS_FILENAME` |
| Perl | **`PERL_FORMAT`** |
| Python | **`PYTHON`** |

The image you have lists its own after an error line, as the longest
`Possible Values` line, when asked for a profile it does not have:

```bash
docker run --rm kaizten/sheriff:latest test -t LIST
```

What each profile checks is Kaizten's to define. The tools know the exact
rules of 12 of them, the ones Sheriff's own export covers. **For one request
only**, name the profile to the assistant ("check the api module with
`JAVA_DDD`"): the tools take it for that request, and the hooks keep to the
project's declaration.

No rule can be switched off: what the profile holds is what is checked.
The one exception is a known false positive of Sheriff's, `FilenamePascalCase`
on `package-info.java` and `module-info.java`, names Java itself requires,
which no tool reports.

| Variable | Default | |
|---|---|---|
| `SHERIFF_REPO` | from the working directory | Directory mounted at `/data` (MCP server) |
| `TARGET_REPO` | from the working directory | The same, for the agent's CLI: the directory that holds the module |
| `SHERIFF_COMPONENT` | the project, or its only component (the agent's CLI with `TARGET_REPO` set: `sheriff-mcp-java`) | Component when a call names none |
| `SHERIFF_PROFILE` / `SHERIFF_TEST_TYPE` | declared, else from the sources / `JAVA` | Rule profile (server / agent), overriding the project's declaration |
| `SHERIFF_IMAGE` | `kaizten/sheriff:latest` | A digest (`kaizten/sheriff@sha256:...`, as `task.json` records it) keeps the rules from changing until you change it |
| `SHERIFF_PULL` | reports a newer image | `always` pulls a newer image on start (the installer's `--pull-always` writes it into the server's registration); `never` does not even ask Docker Hub |
| `SHERIFF_TIMEOUT` | `300` | Seconds per run; the container is removed after |
| `SHERIFF_RULES_CATALOG` | found or extracted | A catalog used as is |
| `SHERIFF_EXPORT_DIR` | unset | Where the agent copies Sheriff's JSON |
| `SHERIFF_STOP_MAX_BLOCKS` | `5` | How many times in a row the Stop hook sends a session back while errors remain, before it lets the turn end so as not to loop. A stop with nothing changed since the last block is sent back once more, saying so; a second such stop in a row goes through, as a model whose edits are denied cannot change anything. The installers give the hook 900 s, as with `SHERIFF_STOP_RUNS_TESTS=1` it runs the tests |
| `SHERIFF_JAVA` | unset | The Java the jar's installers write into the user's configuration (Codex's and Antigravity's server, and the hooks of every project) instead of `java` found on the PATH. Both installers set it to the Java they found, and register Claude Code's server with that Java too, so that an editor started from the desktop, with another PATH, and the hooks under Git Bash on Windows, still start it. Ignored when it names nothing that can be run; a project's own hooks always run `java` |
| `SHERIFF_FAIL_FAST` | unset | `1` makes the hooks run Sheriff with `--fail-fast`: it stops at the first error, so the check is quicker and reports one finding. Never used by the loop or `sheriff_fix`, which need all of them. The installers write it as an option of the hook command instead (`install.sh --fail-fast`) |
| `VERIFICATION_TEST_CMD` | from the build tool | The project's test command |
| `AI_BACKEND` | `claude_cli` | Who answers in the loop, below |

| `AI_BACKEND` | Needs |
|---|---|
| `claude_cli` | A logged-in `claude`; no API key. Edits only inside the component, and may run the project's test command and nothing else |
| `codex_cli` | A logged-in `codex`; runs with `--cd <component>` |
| `antigravity_cli` | A logged-in `agy`, Google Antigravity's command line. Runs inside the component with `--mode accept-edits`, which writes only there, and may run the project's test command and `git mv`, nothing else: each pass gets a `HOME` of its own whose settings, a copy of yours, allow those two, and it is deleted after, so your own settings are never written. On Windows, where that has not been tried, it runs no commands. `ANTIGRAVITY_COMMAND` (`agy --mode accept-edits`) picks the model: `... --model claude-sonnet-5-5-high`, from `agy models`; `SHERIFF_AGENT_ANTIGRAVITY_TIMEOUT` (`600`) the seconds per pass |
| `anthropic_api` | `ANTHROPIC_API_KEY`; `ANTHROPIC_MODEL` |
| `openai_api` | `OPENAI_API_KEY`; `OPENAI_MODEL`, `OPENAI_BASE_URL` |
| `local` | Any OpenAI-compatible server (Ollama by default, `OPENAI_BASE_URL` for others) |

The three API backends edit through two tools of the agent's own,
`read_file` and `write_file`, and both are kept to the component: a path
outside it, by `..` or by a symbolic link that leads out, is refused, so a
model never reads the other projects beside a single-module one, nor writes
into them.

With Ollama, raise the model's context first (its default of 4096 tokens is
shorter than a prompt plus a file): a Modelfile with `PARAMETER num_ctx 16384`,
and `OPENAI_MAX_TOKENS=12000` for a model that thinks before answering.

## The rule catalog

Sheriff ships no rule documentation, so the catalog (529 rules in the image of
5 October, 89 with a fixer) is extracted from the image, and every tool does it by itself when the
catalog is missing or comes from another image: the MCP server in the
background at startup, and again if the image changes during a session; the
agent and `sheriff:fix` before they read it. All three share
`~/.cache/sheriff-mcp/`. To do it by hand:

```bash
cd sheriff-mcp-java && java -jar target/sheriff-mcp.jar --agent --extract-rules
```

**It is not in version control**: it is read out of Kaizten's proprietary
image, so it is not this repository's to publish. Without it nothing breaks;
the rule lookups say there is no catalog.

The extractor is a workaround. Sheriff's `tree --export-profile JSON` lacks
each rule's reference code, whether it has a fixer and its message, and
covers 12 of 35 profiles. If Sheriff exported those, the extractor, the
bytecode reading and the 6 s of startup would go.

## Build and test

```bash
mvn install          # both modules in order, with their tests; no Docker needed
scripts/e2e.sh       # against real Sheriff and git, with a stand-in model; needs Docker
scripts/playbook.sh  # the user's playbook, both languages, into playbook/build/; needs TeX
```

CI (`.github/workflows/ci.yml`) runs both on every push to `main` and every
pull request; `.github/workflows/playbook.yml` builds the playbook when its
sources change, and keeps the two PDF files as the run's artifact. The MCP module also runs in a container (`sheriff-mcp-java/Dockerfile`),
with the host's Docker socket and the repository mounted at its own path:

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:$PWD" \
  -e TARGET_REPO="$PWD" -e SHERIFF_COMPONENT=my-module \
  ghcr.io/kaizten/sheriff-mcp-server --check-only
```

That image is published by `.github/workflows/image.yml` each time CI passes
on `main`, as `latest` and `sha-<commit>`. Whether it can be pulled without
`docker login ghcr.io` is the package's own visibility setting on GitHub.
It carries no rule catalog, and extracts one on first use.

**A release** is published by `.github/workflows/release.yml` when a tag
`v<version>` is pushed, the version being the one in the poms: the jar built
and tested there, its checksum, the two installers and the playbook in both
languages. Then it runs the install line on Linux, macOS and Windows (both
PowerShells) against what it published. A tag with a suffix (`v1.0.1-rc.1`)
is a prerelease, which the install line never picks: it is how a release is
tried before its real tag.

```bash
git tag v1.0.1 && git push origin v1.0.1     # after the version in the poms says 1.0.1
```

## What Sheriff could change

Most of the defensive code here exists for one of these. From Sheriff's side:

1. JSON only on stdout: no header, no `COMMAND:` traces, no usage banner.
2. Exit codes that mean something (`test` always exits 0, `fix` always 1).
3. Telling "nothing was analyzed" apart from "0 errors".
4. A rule export with reference code, fixer and message, for every profile.
5. State files in a configurable path, not the root of the mount.
6. Jar fixers that run: both in the image of 21 September are broken.
7. `FilenamePascalCase` exempting `package-info.java` and `module-info.java`.
