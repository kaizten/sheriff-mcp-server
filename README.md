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
there is a `claude` command or a `~/.claude` folder (its desktop app and IDE
extensions use it), with the hooks for every project and, with `claude`, the
MCP server registered at user scope as `sheriff`; Codex the same way when there
is a `codex` command or a `~/.codex` folder (`CODEX_HOME`), and Antigravity
when there is an `agy` command. A command is looked for on the PATH and then in
the folders npm, nvm, Homebrew and the assistants' own installers put it in;
one found off the PATH is set up all the same, and the installer prints the line that puts it on
yours, except for one in a folder of one of nvm's Node versions, which nvm puts on the PATH itself. It ends saying which it set up, and for each assistant it did not find,
the command that sets it up by hand. With none of them it writes no assistant's configuration and
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
| `--pull-always` (`-PullAlways`) | Writes `SHERIFF_PULL=always` into the server's registration. That is the default now (the server pulls a newer Sheriff image each time it starts), so the option changes nothing; it is kept so that older install lines still work |
| `--install-docker` (`-InstallDocker`) | Installs Docker when it is missing, from Docker itself: `get.docker.com` on Linux (`pacman` on Arch, which Docker does not package for), Docker Desktop's installer from docker.com on macOS and on Windows (there per user, with no administrator and WSL 2, as Docker recommends); and starts it when it is stopped. Docker Desktop asks you to accept its terms the first time it starts, and the installer does not accept them for you: they require a paid subscription in a company of more than 250 people or $10 million a year |
| `--docker-wsl` (`-DockerWsl`) | Windows only (Git Bash or PowerShell): uses the Docker engine installed inside WSL, with no Docker Desktop. It checks `wsl docker info`, pulls the image through WSL and sets `SHERIFF_DOCKER=wsl` for the user, which the server and the hooks read to run every `docker` as `wsl.exe -e docker`, with the mounted folder written as WSL sees it (`C:\x` as `/mnt/c/x`). `SHERIFF_DOCKER=wsl:<distro>` names a distribution other than the default. It is also written into each assistant's registration (`claude mcp add -e`, the `env` of Codex's and Antigravity's entries, and Codex's hook lines), as Codex passes a server and its hooks none of the user's variables. Restart Claude Code and terminals afterwards |
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
the profile by itself. Sheriff is given the component alone, a project that is
itself one module included, and leaves nothing behind in it. Nothing else is needed on this
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
| "a newer kaizten/sheriff:latest has been published ... could not be downloaded" | The server tried to update the image on start and Docker could not pull it (the reason is in the note); the one on this machine is used meanwhile. Run `docker pull kaizten/sheriff:latest`, or restart the session |
| "a newer kaizten/sheriff:latest ... is being downloaded" | The server found a newer image on start and is pulling it; until it is here, answers come from the one on this machine. Nothing to do |
| "The analyzer could not run" | Docker stopped after the image was pulled |
| "The Sheriff server cannot load its own code" | An update on Windows replaced the jar under the running server: restart the session |
| "A Sheriff server was running from the jar this replaced", while installing on Windows | Windows lets no one delete or rename a jar a running server holds, so the installer overwrites it, and that server fails at its next call: restart the open sessions |
| "The analyzer could not run: did not respond within 300s" | Sheriff's time grows with the component it analyzes, and through WSL's Docker a project on `C:` takes far longer than one on WSL's own disk. Raise `SHERIFF_TIMEOUT` |
| "Nothing was analyzed" | The component holds no sources of that profile's language: pass `component`, or check the profile the project declares |
| "No rule catalog is available" | No Docker when the server first started: start Docker, and the next rule lookup tries again (at most a minute later) |
| The hooks do nothing | The session did not pick up the change to its settings: restart it, and check them with `/hooks` |
| "the repair was undone since, so no fixer makes it again" | One of Sheriff's fixers changed something the assistant then put back, usually because it broke the build (on PetClinic under Java 17, checkstyle raised to a version that needs Java 21). `sheriff_fix` does not make it again, and the error is left for the end and told to you with its reason. Nothing to do |
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
| `sheriff_fix` | With no arguments, every repair Sheriff has for what it finds, round after round while the count drops; reports errors before and after, and with `verify` runs the tests and measures how well they test the change (below) | no |
| `sheriff_guidelines` | The rules, before anything is broken, with the code Sheriff accepts | no |
| `sheriff_autofix` | The agent's whole repair loop, on its own branch. Answers at once with a task id and runs in the background | yes |
| `sheriff_task` | A task's command, state and result; without an id, the server's version, the build of Sheriff's image on this machine (the day it was built and its digest, as `latest` names whichever was pulled last), and the session's tasks | no |
| `sheriff_config` | Without arguments, every setting below with its value and where it comes from; with `setting` and `value`, changes one in your settings file (or the project's, with `project` true), checking the value first; with `page` true, opens the settings page below and answers with its address | no |

**A change is not finished until its tests test it.** In a Java component
built with Maven, once it is clean, the step is `sheriff_fix` with `verify`:
it runs the tests and then measures the lines the change wrote (those of
`src/main/java` the last commit does not have), never the whole project.
Three measures, through the project's own Maven, with nothing added to its
`pom.xml`: which of those lines the tests run, and which branches of their
conditions they take, each side of every `&&` and `||` (JaCoCo); and PIT's
mutation testing, which changes those lines (each condition forced true and
false, a limit moved, a value returned replaced, a call's argument put in
place of its result) and the tests must fail on each change. A line run is not
a condition tested: `if (member && units > 2)` runs whichever way it goes, and
is four branches. The answer gives the three shares and, below what the
project asks, lists the lines no test runs, the conditions taken only some
ways and the changes no test noticed, and sends the model back to write
tests:

```
The tests run 4 of the 8 lines of code this change wrote (50%, 100% required), take 3 of the 8 branches of their conditions (37%, 100% required) and kill 10 of the 27 mutants PIT made of them (37%, 80% required).
Lines of code this change wrote that no test runs:
  - src/main/java/demo/Calc.java: 10, 13, 14, 16
Conditions this change wrote that the tests take only some ways, each side of every && and || counted:
  - src/main/java/demo/Calc.java:20 takes 3 of its 4 branches
Changes PIT made to those lines that every test still passed with:
  - src/main/java/demo/Calc.java:20 changed conditional boundary
```

By default every changed line must run, every branch be taken and 80% of the
mutants be killed (not all: some mutants change nothing a test could see).
The mutants are made with `STRONGER,REMOVE_CONDITIONALS,EXPERIMENTAL_ARGUMENT_PROPAGATION`,
not PIT's `ALL`, which on PetClinic's `owner` package made 497 mutants to
these 204, most of them constants such as `setDisallowedFields("id")`'s, which
no sensible test kills. A project declares its own:

```xml
<properties>
  <sheriff.coverage>90</sheriff.coverage>
  <sheriff.branches>90</sheriff.branches>
  <sheriff.mutation>70</sheriff.mutation>
  <sheriff.mutators>ALL</sheriff.mutators>
</properties>
```

or `coverage`, `branches`, `mutation` and `mutators` in `.sheriff.properties`. JaCoCo and PIT
are downloaded by Maven the first time (with JUnit 5, PIT's JUnit 5 plugin
too). A measure that cannot run (no network, a surefire `argLine` that drops
JaCoCo's agent: it must keep `@{argLine}`) does not hold the work: the answer
says why and the component is done; so does PIT refusing a suite with a test
that fails in its own runner. PIT does not read surefire's configuration, so
the measure passes it surefire's `systemPropertyVariables`, all but those
whose value holds a space, which PIT cannot take. Gradle and npm projects are not measured
yet.

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
`SHERIFF_STOP_RUNS_TESTS=1`, with failing tests, or with passing tests that do
not reach the line, branch and mutation thresholds for what the turn wrote). Which components the turn
changed is the third hook's job: when a prompt arrives it records what the
components git already reports as changed look like, and the Stop hook leaves
alone those still the same, so a question asked in a repository with earlier
work in it is not sent back to fix code. A component the gate refused an edit
to during the turn is checked too, though the turn changed nothing in it: a
model whose only edit was refused ended the turn asking whether to fix the
errors. That decision is the hooks', not the model's. An error that stays
through the work done for it (two answers of `sheriff_fix` after it was
listed, or the Stop hook's stops with nothing changed running out) is left for
the end: the answers list it apart, after the files still being worked on,
then give it a last round of its own, and after that tell the model to name it
to the user with the reason it cannot be fixed. Meanwhile a component whose
only errors are those blocks no edit and sends no turn back, so a false
positive of the checker does not hold the rest of the work up. A hook has already analyzed, so its message ends with the step an
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

**In Maven**, with no configuration: the module's directory is the component,
and Maven knows where it is.

```xml
<plugin>
  <groupId>com.kaizten</groupId>
  <artifactId>sheriff-maven-plugin</artifactId>
  <version>0.1.10</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

`sheriff:check` runs in `package`, after the tests, so `mvn package`, `mvn verify`
and `mvn install` all fail on errors; an execution with its own `<phase>`
moves it. `mvn sheriff:fix` is bound
to no phase, because it rewrites source; it analyzes again after repairing and
fails if errors remain, one module at a time even in a parallel build. Both
leave Sheriff's JSON in `target/sheriff/` and skip modules with no `src/`.
Parameters: `sheriff.profile` (the project's declaration, else `JAVA`), `sheriff.image` (`kaizten/sheriff:latest`),
`sheriff.timeout` (the project's `SHERIFF_TIMEOUT`, else `300`),
`sheriff.failOnError` (`true`), `sheriff.failOnRemaining` (`true`), `sheriff.skip`. The other settings come
from the `.sheriff.properties` at the top of the build too, as the MCP server reads it; never from your own
file, so a build does not depend on who runs it.

## Codex

The same jar works from Codex, and `scripts/install.sh` sets it up for every
project when it finds `codex` or `~/.codex`, as it does Claude Code; Codex
need not be on the PATH, as setting it up only writes `~/.codex`. Tested on
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
in its state folder, under `~/.cache/sheriff-mcp/state/`, outside the project;
the next run clears them by itself. In an interactive session the loop runs on between turns, and
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
server works from it. `scripts/install.sh` sets it up when it finds `agy`
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
| `--compare-reports PATH...` | Reports of runs and sessions side by side (*Comparing runs and sessions*, below); `--csv DIR` writes the tables too |

Loop flags: `--max-iterations N`, `--max-files-per-batch N` (5),
`--batch-by file|rule` (`file`: every error of those files; `rule`: one rule's
errors in those files, the rule with most first), `--max-errors-per-batch N`
(0, no cap: at most N errors a pass, whole files while they fit, and the first
N of a file that alone has more), `--no-git-safety` (no branch, no commits, no scope check), `--json-report PATH`.
With no `--max-iterations`, the cap is sized from the first analysis (one pass
per five files with errors, and two to spare) and grows after every pass that
lowers the errors, to what the files still with errors need, up to three times
that first estimate: a project analyzed for the first time, with errors in
hundreds of files and many of them needing a second pass, used to run out of
the two spare passes with work left. A pass that lowers nothing parks its files
instead, and with nothing left to try the run stops.
A pass that touches a file outside its batch stops the run without committing,
and the work is left in the tree for review.

Every pass is measured by the analysis after it, and written as a JSON line to
`metrics-<when the run started>.jsonl` beside the run's prompt logs
(`PROMPT_LOG_DIR`): `kind` (`deterministic` for Sheriff's own fixers, `model`
for the model's), `elapsed_ms`, `errors_before` and `errors_after` rule by rule
in the pass's files (the whole component for Sheriff's fixers; `null` when no
analysis followed), `repaired`, and `usage`: the models, the input, output and
prompt-cache tokens, the turns and the cost. Claude Code's cost is at list
price, also on a subscription; Codex reports none, and its fixer adds `--json`
to a `codex exec` so that it reports its tokens.

**A run's report.** With `SHERIFF_REPORT=1`, a setting of your own file (or a
variable), every run of the loop, `sheriff_autofix`'s included, ends by
writing one JSON document of itself to
`~/.local/state/sheriff-mcp/reports/loop-<when>-<component>-<backend>.json`
(under `XDG_STATE_HOME` when it is set), and says where; `--json-report PATH`
writes the same to `PATH` with the setting off. It holds counts, rule codes
and paths, never a finding's text, which can quote code:

| Field | What it holds |
|---|---|
| `schema`, `kind` | `1` and `loop`: a field added keeps the schema, one changed raises it |
| `ok`, `stopped_reason`, `iterations_used`, `max_iterations`, `repair_pass_used`, `parked_files`, `working_branch`, `tests_passed` | How the run ended, as `--json-report` has always said |
| `started`, `finished`, `elapsed_ms` | When, and for how long |
| `tools`, `sheriff` | This jar's version; Sheriff's image, the digest it was pulled by and its build date |
| `project` | The repository, the component, the profile and the commit it started from |
| `configuration` | `backend`, `model` asked for (`null`: the backend's own), `batch_by`, `max_files_per_batch`, `max_errors_per_batch`, `max_iterations` (`null`: sized from the errors), `git_safety`, `sheriff_fixers`, `repair_rounds`, `max_rules_in_prompt`, `prototype` |
| `initial`, `final` | The errors it started from and left: `total`, `files`, `by_rule`; `final.after_last_pass` is `false` when no analysis could follow the last pass |
| `totals` | `passes`, `model_passes`, `repaired_by_sheriff`, `repaired_by_model`, `introduced_by_model`, `model_ms`, `sheriff_fixers_ms`, `models` and the tokens and cost |
| `passes` | Every pass as its `metrics-*.jsonl` line, with `files` and `given` (what a model's pass was handed) and `introduced` (what each rule gained) |

A run cut right after a pass (its fixer failed, or it changed a file outside
its batch) is analyzed once more, so `final` is what is in the tree.

**A session's report.** With the same setting, every session of the MCP
server writes `session-<when>-<project folder>.json` beside them, rewritten
after every call, so a session a client stops without warning (Codex does)
still has it. It says which assistant connected (`client`: the name and
version its `initialize` gave),
this jar's version, Sheriff's image and the project, and every call: its
`tool` and `arguments`, `elapsed_ms`, whether it `failed`, and the
`analyses` it made, rule by rule, `before` and `after` for a repair. Its
`totals` add up the calls, by tool and failed, their time, the errors the
first analysis found (`first_errors`) and the last analysis of each component
(`last_errors`). What the assistant's model spent is not visible to the
server, and is not in it.

**Comparing runs and sessions.** `--compare-reports` reads the reports in the files
and folders it is given (with none, the current folder), at any depth, and
prints them side by side as Markdown: by configuration, the mean of its runs;
by run; and by rule, what was found, repaired by Sheriff and by the model,
introduced and left. A configuration is a project (its component and the
commit it started from), a backend, a model and a strategy, written as the
benchmark writes one (`file:5`, `rule:5`, `file:0:20`). `--csv DIR` also
writes `configurations.csv`, `runs.csv`, `rules.csv` and `passes.csv`.
Sessions are compared apart, by client and project (the mean of its
sessions) and by session, with their calls, `sheriff_fix` calls, the errors
they started from and left, and the time in their calls; `--csv` adds
`sessions.csv`. It runs no Sheriff, and passes over, and counts, any file
that is no report:

```bash
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --agent --compare-reports ~/.local/state/sheriff-mcp/reports
java -jar ~/.local/share/sheriff-agent/sheriff-mcp.jar --agent --compare-reports a.json b.json --csv tables/
```

**The benchmark.** `scripts/benchmark.sh` runs the loop over a few public
projects at pinned commits, or over your own with `--repo PATH[:COMPONENT]`
(cloned at its HEAD for every run, so each starts from the same code and the
repository is never touched), with each backend and model asked for, each
strategy, `file:N` (every error of N files a pass), `rule:N` (one rule's
errors in N files) and either with `:E`, at most E errors a pass (`0` is no
limit), and as many runs of each as asked. Every run writes its report, and
the comparison above makes the tables, in `summary.md` and the CSV files:

```bash
scripts/benchmark.sh --runs 3 --backend claude_cli:claude-sonnet-5-5 --backend codex_cli
scripts/benchmark.sh --strategy file:5 --strategy rule:5 --strategy file:0:20
scripts/benchmark.sh --repo ~/code/shop --repo ~/code/monorepo:api --strategy file:5 --strategy file:0:10
scripts/benchmark.sh --report ~/.local/state/sheriff-mcp/benchmark/<run>   # the tables again
```

It clones those projects, runs their builds and lets the model edit them, and
spends tokens: on PetClinic a run is some five passes.

## Configuration

Nothing is required: the server works out the mount, the components, each
one's profile and the test command from the project it is started in.

**Every setting has a default, and you change it in a file, not in each
assistant.** The first time the server starts it writes
`~/.config/sheriff-mcp/sheriff.properties` (`$XDG_CONFIG_HOME/sheriff-mcp/`,
or `%APPDATA%\sheriff-mcp\` on Windows), every setting in it at its default
and commented out, under a line saying the values it takes: take the `#` off
a line and change its value. A setting a
newer version brings is added at the end of the file when the server next
starts, commented out the same way; nothing already in it is touched. A project's
`.sheriff.properties` can set the same names for everyone who opens it (all
but who answers in the repair loop and how: `AI_BACKEND`, `SHERIFF_AGENT_*`,
`CODEX_COMMAND`, `ANTIGRAVITY_COMMAND` and the APIs' `ANTHROPIC_*` and
`OPENAI_*`, which a cloned project must not choose), and an environment variable of the same name wins over both
files.

**The easiest way is a page in your browser**: `java -jar sheriff-mcp.jar
--config-ui` opens it (the installers name the line), and so does asking the
assistant for Sheriff's settings page (`sheriff_config` with `page`, which
answers with its address). Asked how to change the settings, the assistant also
gets, in `sheriff_config`'s answer, the exact command that opens the page from a
terminal on that machine. The page lists every setting by its name under its
section, with what it does, what more or less of it changes, what each option
means, the value in force and where that comes from. Changes are made in your
settings or, with *This project*, in the project's file, and saved together.
It speaks English and Spanish: it opens in the browser's language and keeps the
one you choose in its header;
a value the tools could not use is refused there, with its reason, as every
other way does. It is served on
`127.0.0.1` only, under a random token no other page can guess, refuses a
request whose `Host` or `Origin` is not that address, and closes with its Close
button, after 30 minutes unused, or with the session that opened it. Or ask the
assistant: `sheriff_config` lists them and changes one. `java -jar
sheriff-mcp.jar --config` prints the same list, and `--config NAME=VALUE` (with
`--project`, in the project's file) changes one the same way. A value written by hand into a file that its setting
does not take, a name that is no setting, or one a project may not set, is
ignored, so the next file or the default decides; the listing ends with what
is wrong, and so does the tools' first answer of a session. The tools read the settings when a session starts, the
hooks on every run. The names are those of the table below, which also holds
the variables no file sets (`SHERIFF_REPO`, `SHERIFF_DOCKER`, the API keys).
Sheriff's image is always `kaizten/sheriff:latest`, kept up to date as
`SHERIFF_PULL` says: it is no setting.

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
| `SHERIFF_PROFILE` / `SHERIFF_TEST_TYPE` | declared, else from the sources / `JAVA` | Rule profile (server / agent), overriding the project's declaration for the components written in its language |
| `SHERIFF_PULL` | `always` | `always` pulls a newer image when the server, `--check`, the agent or a Maven goal starts: newer means the digest Docker Hub publishes for the tag differs from the one the image here was pulled by, as Sheriff is only published as `latest`, with no versions. `check` only reports it, once per session; `never` does not even ask Docker Hub |
| `SHERIFF_UPDATES` | `notify` | What happens when a newer version of these tools is published, looked for at most once a day when the server starts, through the public `releases/latest` address (no API, no token). `notify`: the first answer of a session says so, with the install line, or `git pull && scripts/install.sh` in a clone. `auto`: the server downloads the new jar, checks it against its release's checksum and puts it in place of the installed one, for the sessions that start after (a jar in a build's `target/` is only told about). `never`: it does not look. Your file only |
| `SHERIFF_TIMEOUT` | `300` (`900` through WSL's Docker) | Seconds per run; the container is removed after. Through WSL (`SHERIFF_DOCKER=wsl`) the default is 900, and Codex is given 1800 s a call instead of 600: a `sheriff_fix` of PetClinic took 511 s there |
| `SHERIFF_DOCKER` | unset | Windows only: `wsl` (or `wsl:<distro>`) runs every `docker` as `wsl.exe -e docker`, for the engine inside WSL with no Docker Desktop. `--docker-wsl` (`-DockerWsl`) sets it for the user and in each assistant's registration |
| `SHERIFF_RULES_CATALOG` | found or extracted | A catalog used as is |
| `SHERIFF_EXPORT_DIR` | unset | Where the agent copies Sheriff's JSON |
| `sheriff.coverage` / `coverage` | `100` | In the `pom.xml` / `.sheriff.properties`: the share of the lines a change wrote its tests must run |
| `sheriff.branches` / `branches` | `100` | Likewise: the share of the branches of those lines' conditions its tests must take |
| `sheriff.mutation` / `mutation` | `80` | Likewise: the share of PIT's mutants of those lines its tests must kill |
| `sheriff.mutators` / `mutators` | `STRONGER,REMOVE_CONDITIONALS,EXPERIMENTAL_ARGUMENT_PROPAGATION` | Likewise: PIT's mutators, or groups (`DEFAULTS`, `STRONGER`, `ALL`), the mutants are made with |
| `SHERIFF_REPAIR_ROUNDS` | no limit | Rounds of Sheriff's own fixers in a repair, each measured by a new analysis. With none set they go on until a round repairs nothing, which they always reach: a round runs only when the one before lowered the count |
| `SHERIFF_GATE_TTL` | `900` | Seconds the hooks reuse a verdict a tool or a hook recorded before running Sheriff again |
| `SHERIFF_STOP_MAX_STALLS` | `1` | How many stops in a row with nothing changed since the last block the Stop hook still sends back; the next goes through. `0` lets the first through, which a checker's false positive that nothing can fix calls for |
| `SHERIFF_PROTOTYPE` | `0` | `1` while an idea is being tried out: a component is done when Sheriff finds no errors, and neither the tools, the Stop hook nor the repair loop ask for, run or measure its tests (new methods without a test, coverage, mutation, `SHERIFF_STOP_RUNS_TESTS`). Set it back to `0` before the work is kept |
| `SHERIFF_TEST_TIMEOUT` | `600` | Seconds the project's tests get when the tools run them (`VERIFICATION_TIMEOUT`, 300, in the hooks and the loop) |
| `SHERIFF_COVERAGE`, `SHERIFF_BRANCHES`, `SHERIFF_MUTATION`, `SHERIFF_MUTATORS` | `100`, `100`, `80`, the set above | What the tests of a change must reach, for every project; a project's `sheriff.coverage` and the rest win |
| `SHERIFF_COVERAGE_TIMEOUT`, `SHERIFF_MUTATION_TIMEOUT` | `600`, `1800` | Seconds the tests get under JaCoCo, and PIT gets |
| `SHERIFF_STOP_MAX_BLOCKS` | `5` | How many times in a row the Stop hook sends a session back while errors remain, before it lets the turn end so as not to loop. A stop with nothing changed since the last block is sent back once more, saying so; a second such stop in a row goes through, as a model whose edits are denied cannot change anything. The installers give the hook 900 s, as with `SHERIFF_STOP_RUNS_TESTS=1` it runs the tests |
| `SHERIFF_JAVA` | unset | The Java the jar's installers write into the user's configuration (Codex's and Antigravity's server, and the hooks of every project) instead of `java` found on the PATH. Both installers set it to the Java they found, and register Claude Code's server with that Java too, so that an editor started from the desktop, with another PATH, and the hooks under Git Bash on Windows, still start it. Ignored when it names nothing that can be run; a project's own hooks always run `java` |
| `SHERIFF_FAIL_FAST` | unset | `1` makes the hooks run Sheriff with `--fail-fast`: it stops at the first error, so the check is quicker and reports one finding. Never used by the loop or `sheriff_fix`, which need all of them. The installers write it as an option of the hook command instead (`install.sh --fail-fast`) |
| `VERIFICATION_TEST_CMD` | from the build tool | The project's test command |
| `AI_BACKEND` | `claude_cli` | Who answers in the loop, below |
| `SHERIFF_MAX_ITERATIONS`, `SHERIFF_MAX_FILES_PER_BATCH` | sized from the errors, `5` | The loop's passes, and the files a model is given at once |
| `SHERIFF_BATCH_BY` | `file` | What a pass's errors have in common: `file`, every error of a few files; `rule`, one rule's errors in a few files |
| `SHERIFF_MAX_ERRORS_PER_BATCH` | `0` | The errors a model is given at once, at most, whatever their files; `0` for no cap |
| `SHERIFF_REPORT` | `0` | `1`: every run of the loop and every session of the MCP server writes its report (see *A run's report*); from your own file only |
| `SHERIFF_MAX_RULES_IN_PROMPT` | the loop's own limit | Rules of the profile put in one prompt |
| `SHERIFF_AGENT_GIT_SAFETY` | `1` | `0` lets the loop edit the working tree as it is: no branch of its own, no commit per pass, no stop when a model edits a file outside its batch. Your file only |
| `CODEX_COMMAND`, `ANTIGRAVITY_COMMAND` | `codex exec --sandbox workspace-write`, `agy --mode accept-edits` | How the loop starts `codex` and `agy`, without the prompt (to add `--model`). Your file only |
| `ANTHROPIC_MODEL`, `ANTHROPIC_BASE_URL`, `ANTHROPIC_MAX_TOKENS`, `ANTHROPIC_MAX_TOOL_ITERATIONS` | `claude-opus-5-5`, Anthropic's API, `16000`, `20` | `anthropic_api`'s model, endpoint, tokens per answer and file reads or writes per pass. Your file only, as a cloned project must not choose where code is sent |
| `OPENAI_MODEL`, `OPENAI_BASE_URL`, `OPENAI_MAX_TOKENS`, `OPENAI_MAX_TOOL_ITERATIONS` | `gpt-4o-mini` / `qwen2.5-coder`, OpenAI's API / Ollama's `http://localhost:11434/v1`, `8000`, `20` | The same for `openai_api` / `local`. Your file only |
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` | unset | The APIs' keys: environment variables only, never written into a file |

| `AI_BACKEND` | Needs |
|---|---|
| `claude_cli` | A logged-in `claude`; no API key. Edits only inside the component, and may run the project's test command and nothing else |
| `codex_cli` | A logged-in `codex`; runs with `--cd <component>`; `SHERIFF_AGENT_CODEX_TIMEOUT` (`600`) the seconds per pass |
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
scripts/benchmark.sh # the loop over public projects, measured; spends tokens (see above)
```

CI (`.github/workflows/ci.yml`) runs both on every push to `main` and every
pull request; `.github/workflows/playbook.yml` builds the playbook when its
sources or a version change, and keeps the two PDF files as the run's
artifact. It caches them by what goes into them, so a run with nothing new
skips installing TeX. The MCP module also runs in a container (`sheriff-mcp-java/Dockerfile`),
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
PowerShells) against what it published. A tag with a suffix (`v0.1.1-rc.1`)
is a prerelease, which the install line never picks: it is how a release is
tried before its real tag.

No workflow runs on Windows now (it is out of the matrices while the product
is not meant for it; `windows-latest` brings it back), so a change that
touches Windows (the installers, the hooks, how commands or Docker are run)
is tried on the prerelease by hand, on a Windows machine, before the real
tag: install it, open a Codex session and a Claude Code session on a project
with errors, and check that `sheriff_test` analyzes, that the Stop hook
blocks the turn, and that it lets the turn end once `sheriff_fix` has left
nothing.

```powershell
& ([scriptblock]::Create((irm https://github.com/kaizten/sheriff-mcp-server/releases/download/v0.1.1-rc.1/install.ps1))) -Tag v0.1.1-rc.1
```

```bash
git tag v0.1.1 && git push origin v0.1.1     # after the version in the poms says 0.1.1
```
