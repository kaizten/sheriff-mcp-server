# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

Two modules around **Sheriff** (`kaizten/sheriff:latest`), Kaizten's code
standards analyzer, which is a Docker image with a CLI and no library
interface. Everything here is a different shape of reaching it:

| | |
|---|---|
| `sheriff-mcp-java/` | **the product**: Sheriff as five MCP tools, so a model can reach it while writing code — one jar, registered once, that works in any project with no configuration. **The agent lives inside it**: the loop that fixes code until Sheriff is quiet, its hexagonal core (`domain/`, `application/`, `infrastructure/`), the rule-catalog extractor, and its CLI, reached as `sheriff-mcp.jar --agent`. The server is a driving adapter in `infrastructure/mcp/`, beside the CLI in `infrastructure/cli/`. The same jar is the CI gate (`--check`) and the three Claude Code hooks (`--hook-gate`, `--hook-stop` and `--hook-turn`, wired for every project by `scripts/install.sh`, or for one by `--install-hooks`), and sets Codex up (`--install-codex`). It hands the client its order of work as MCP instructions (`src/main/resources/instructions.md`) |
| `sheriff-maven-plugin/` | the same check as a Maven goal, bound to `package` (so `mvn package` runs it), and `sheriff:fix`, which fails when errors are left |

**The interaction is** developer → assistant (with skills)
→ MCP server → agent → Sheriff, whose output is JSON (or, as a different kind
of output this repository does not build, PostgreSQL). That is why the agent
is inside the MCP module rather than beside it: until September 2026 it was a
third module, `sheriff-agent-java`, kept in the history of the private
repository this one was published from. And it is why the
server works in tasks: `sheriff_autofix` answers at once with a task id and
runs in the background, `sheriff_task` reports id, command and state, and
every call that runs Sheriff is recorded as one, under
`${XDG_STATE_HOME:-~/.local/state}/sheriff-mcp/tasks/<id>/`. One folder per
call would grow without bound, so each session prunes once, on its first
task, keeping the newest `TaskRegistry.RETAINED_FOLDERS` and only ever
touching id-shaped folders that hold a `task.json`.

Two things that were there are left out of this one, on purpose:
`mcp-server/`, a second independent implementation of the MCP tools kept to
compare "share the core" against "stay independent" (sharing won: it is what
makes `sheriff_autofix` possible), and `skills/code-standards/`, whose order
of work became the server's MCP instructions and whose hooks became
`--install-hooks`. The rules-only skill is Sheriff's own
(`ai --skill --provider ANTHROPIC -t <profile>`), not this repository's.

**Documentation is kept to two files**: `README.md` (install, use,
configuration) and this one. Do not add module readmes or plans back; put a
fact in the one whose reader needs it. One more has a reader of
its own, the person who installs a release and never reads the code:
`playbook/`, the user's playbook in Spanish and English (LaTeX, one style and
one `.tex` per language), built by `scripts/playbook.sh` and attached to every
release as `sheriff-playbook-es.pdf` and `sheriff-playbook-en.pdf`. It holds
plays, not reasons. A change to what a user types or reads (the install line,
an option, a tool's answer, a hook's message) goes into both of its languages
in the same change, and its answers are copied from a real run, not written
by hand. It must build with only the TeX packages `release.yml` installs on
Ubuntu: `pifont` built locally and broke there (its ZapfDingbats font is not
among them), which would have failed the release after its tag;
`playbook.yml` builds it on the pull request to catch that.

**Docker and the `kaizten/sheriff` image are required** for anything that
actually analyzes. The test suites are not: none of the tests needs Docker.

## Commands

The two Java modules are aggregated by the root `pom.xml`, and **the order
matters**: the Maven plugin depends on the MCP module's plain artifact
(`sheriff-mcp-core.jar`, which carries the agent's core), so on a clean clone
it cannot build until that is installed. From the root, Maven works that out:

```bash
mvn install                                   # both, in order, with their tests
scripts/install.sh                            # the same, then installs the jar and registers the MCP
curl -fsSL https://github.com/kaizten/sheriff-mcp-server/releases/latest/download/install.sh | bash   # a release, no clone
scripts/e2e.sh                                # against real Sheriff and git, no model: needs Docker
scripts/ci-local.sh                           # before every push: CI's own steps, here (--no-build, --e2e)
scripts/playbook.sh                           # the user's playbook, both languages: needs TeX
```

`scripts/e2e.sh` is the check for what the unit suites cannot see because
they run without Docker: which branch the loop edits, whether the scope check
sees a module-that-is-a-repository, whether the MCP's stdout is clean, whether
a timed-out container is really gone. A stand-in `claude` plays the model, so
it costs nothing. Every scenario in it was a real bug; run it after touching
the loop, git, the hooks, the MCP entry point or how containers are launched.

**Run `scripts/ci-local.sh` before pushing, and push only when it passes.**
It reads the steps out of `ci.yml` by name and runs them as written, in a
HOME, a Java `user.home` and a PATH of their own (one step removes
`~/.claude`; with the real `claude` the installers would set this machine
up), plus `install.ps1` in PowerShell 7 in a container, `install.sh` under
bash 3.2, the workflows' syntax and the playbook. It exists because PR #10
found three Windows failures in a row on GitHub, each one a round trip. It
cannot reach Windows PowerShell 5.1, cmd, Git Bash, winget or macOS, and says
so: a step for those is still tried first on GitHub, and that is the only
thing CI should be the first to see. A new CI step that can run on Linux
belongs in it too.

`scripts/install.sh` copies the runnable jar and the catalog into
`~/.local/share/sheriff-agent/` and **sets up only the assistants the machine
already has**, never installing one, then says in its last line which. For
Claude Code (`claude` on the PATH, or `~/.claude`, which the desktop app and
the IDE extensions read without putting `claude` on the PATH) it registers the
MCP server at user scope as `sheriff` when there is a `claude` to do it with,
and wires the hooks into `~/.claude/settings.json`
(`--install-hooks --user`), so that no project needs a step of its own,
together with `permissions.allow` rules for the four sheriff tools that do
not commit or spend tokens (not `sheriff_autofix`): without them a model with
no auto mode stopped at a prompt on its first `sheriff_fix`, and
`--uninstall-hooks` takes out only those rules.
With `codex` on the PATH it sets Codex up the same way (`--install-codex`,
`CodexInstaller`): the server in `~/.codex/config.toml` with the time limit
and the approval `codex mcp add` cannot write, the order of work in
`~/.codex/AGENTS.md`, which Codex reads in every project, and the Stop and
turn hooks, without the gate, in `~/.codex/hooks.json`. The one step left to
the user is Codex's review of a new hook, and it stays there on purpose; the
installer and the playbook say what skipping it costs, as on PetClinic
(9 October) Codex twice stopped with over a hundred errors left and only the
Stop hook sent it on. With
no assistant at all it writes nothing into anyone's configuration (it used to
create `~/.claude/settings.json` for a Claude Code nobody had) and ends saying
the jar is installed only as the CI gate and the Maven plugin; `install.ps1`
does the same. `--no-hooks` leaves the
hooks out, `--no-codex` leaves Codex alone, `--no-antigravity` leaves
Antigravity alone, `--fail-fast` writes the hooks as
`--hook-stop --fail-fast` (an option, not a variable: Codex passes a hook
almost none of the shell's), `--pull-always` writes `SHERIFF_PULL=always` into
the server's registration in both assistants (`claude mcp add -e`, and
`--install-codex --pull-always` as `env` in the entry), and `--no-mcp`, which
CI uses with `--no-pull`, touches no assistant's configuration at all.

Every installer writes an assistant's file through `ConfigFile`: whole, where
a symbolic link points, and **with the permissions it had**. A `settings.json`
or a `config.toml` only its owner could read used to come out of an install
readable by every user, and both can hold tokens. The server, and the hooks of
every project, run the Java the installer found, by its absolute path
(`SHERIFF_JAVA`, read by `--install-hooks --user`, `--install-codex` and
`--install-antigravity`, and given to `claude mcp add`): an editor started
from the desktop can have another PATH, and the server then only showed
"connection closed". Both installers look in `JAVA_HOME`, then the PATH, then
where JDKs are installed, so a JDK that is on no PATH is found. `install.ps1`
used to pass none, as Windows installs each JDK update in a folder named after
its version; but Claude Code runs hooks in Git Bash, where a Temurin installed
without its PATH entry is not found, and every hook failed as "non-blocking"
(9 October). A Java update that removes the old folder now needs the install
run again, which the playbook says. With no Java, `install.ps1` installs
Temurin 21 with winget. Under
Git Bash, `install.sh` writes paths as `C:/x` (`cygpath -m`). Both end by
starting the server as registered and sending it `initialize`, and fail
unless `serverInfo.name` is `sheriff`. A project's own
`.claude/settings.json`, which a team commits, keeps `java`. An option the
jar does not know is refused with exit code 2; it used to start the MCP server, so `--instal-hooks` waited on standard input or ended
with nothing done and 0.

**The same script installs a release**, which is how anyone without a clone
gets the tools: read from a pipe, or with `--release[=TAG]`, it has no
checkout, so it downloads the release's jar and checksum by their public URLs
(`releases/latest/download/`, or `releases/download/<tag>/`), with curl or
wget, instead of building. It used `gh`, logged in to any account, while the
repository was private; public, that was a login and a tool every user had to
set up for nothing, and on Windows a new window too. Those URLs never name a
prerelease, and `release.yml` runs the line with no token to keep it so.
Everything runs from `main()` on its last line, so bash has read the whole
script before any of it runs, and nothing in it reads standard input, which
in `curl | bash` style is the script itself. By default it then pulls Sheriff's
image, so that the first check does not wait 4 GB; `--install-docker` installs
Docker from Docker itself (`get.docker.com`, `pacman` on Arch, Docker Desktop's
installer from docker.com on macOS and, per user, on Windows) and never accepts
Docker Desktop's terms for the user. `install.ps1` mirrors it, with two rules
of its own: it never calls `exit`, which run as a script block closes the
window, and it is ASCII only, as its line fetches it with `irm`, and GitHub
serves it as `application/octet-stream`, with no charset to decode it by. It
is run as a script block rather than `irm | iex`, which would run it in the
user's session and leave its variables and its `$ErrorActionPreference`
there. Its native commands run for an exit code go through
`Test-Native`: Windows PowerShell turns a redirected stderr into errors, and
`claude mcp remove ... *> $null` used to abort a first install there.

**Changing `HOME` does not keep a trial install off the real configuration.**
`claude` follows `HOME`, but the jar finds the home through Java's
`user.home`, so on 7 October `--install-hooks --user` and `--install-codex`
rewrote the real `~/.claude/settings.json` and `~/.codex/` to point at a jar in
a scratch folder. Add `JAVA_TOOL_OPTIONS=-Duser.home=<dir>` (and
`CODEX_HOME`), use a container, or install with `--no-mcp` and `SHERIFF_HOME`,
as CI does.

The install folder is what the hooks point at, and a jar there resolves its
catalog and logs beside itself. A registration pointing
into `target/`, or at a module that has been removed, fails at session start
as "connection closed"; re-run the script after pulling.

`.github/workflows/ci.yml` runs both on every push to `main` and every pull
request: `mvn -B install` first, then `scripts/e2e.sh` against the pulled
image. A red e2e with a green build can be a new image rather than the code.
On each of the three systems it also runs the installer as a user's would,
which `--no-mcp` never reaches: with no assistant, when it must write into no
one's configuration, then with stand-in `claude`, `codex` and `agy` that only
record their calls, under `/bin/bash` (3.2 on macOS, Git Bash on Windows) and
Windows PowerShell; and on Windows once more with Java on no PATH and no
`JAVA_HOME`, where it must be found under Program Files.
`.github/workflows/image.yml` publishes the agent's image,
`ghcr.io/kaizten/sheriff-mcp-server`, when CI passes on `main`, and only
builds it on a pull request. It fails rather than publish the rule catalog:
the Dockerfile copies one that sits beside it, which a local checkout may have.
`.github/workflows/release.yml` publishes a release when a tag `v<version>` is
pushed: it fails unless the tag is the version the jar reports, and unless the
jar is free of the catalog, and then runs the install line on Linux, macOS and
Windows (Windows PowerShell and PowerShell 7) against what it published. A tag
with a suffix is a prerelease, which the install line never picks, and that is
how a release is tried first.

No stated test count here or in the readmes: every one of them drifted from
what the suites ran. Read `Tests run:` from the build instead.

No module declares the aggregator as its parent, so each still builds on its
own once the MCP module is installed:

```bash
# The MCP server, with the agent inside
cd sheriff-mcp-java
mvn package                                   # target/sheriff-mcp.jar, runnable; -core.jar is the library
mvn test
mvn test -Dtest=LayeringTests                 # one class
mvn test '-Dtest=FixLoopTests$Batching'       # one nested class (quote it)
java -jar target/sheriff-mcp.jar --tools      # the tool definitions
java -jar target/sheriff-mcp.jar --agent --help

# The Maven plugin (needs the MCP module installed first)
cd sheriff-maven-plugin
mvn install
mvn com.kaizten:sheriff-maven-plugin:1.0.1:check    # on any Maven module
```

The agent is configured entirely by environment variables — `TARGET_REPO`,
`SHERIFF_COMPONENT`, `SHERIFF_TEST_TYPE`, `AI_BACKEND` — because it is also
launched as a hook and as an MCP-less CLI. The README has
the table, including `SHERIFF_EXPORT_DIR`.

## Things about Sheriff that will waste your time otherwise

- **Sheriff analyzes a *subdirectory* of what it mounts.** So a component is
  always "mount the parent, name the directory". A repository whose root *is*
  the module mounts its parent. **Nested components (`a/b`) are rejected.**
  This is the single most common source of confusion here, and it is why the
  Maven plugin needs no configuration: Maven already knows both halves.
  **Its time grows with the whole mount, not the component.** Measured on 9
  October, PetClinic under `JAVA` through WSL's Docker: 1.6 s on WSL's own
  disk, 26 s from `/mnt/c` in a folder holding only it, 7 min 20 s from
  `/mnt/c` with its parent being a 4.8 GB folder of other repositories, past
  `SHERIFF_TIMEOUT`'s 300 s.
- **`test` always exits 0**, however many errors it found, and **`fix` always
  exits 1**, whatever happened. Neither exit code carries information. Decide
  what happened from the output, or by analyzing again.
- **A fixer that cannot run looks like one that changed nothing**, and one
  that lies looks like one that worked. `fix` exits 1 either way. Two
  different defences, and both are needed:
  - A fixer that *fails* says so on stdout: `Could not apply fixer for error
    'CODE'`, then `Error: <reason>`. `FixerFailures` reads those lines and
    every report of a repair names them.
  - A fixer that *exits 0 having done nothing* says nothing at all. Only
    analyzing again catches it, which is why a repair is always followed by
    another analysis: `sheriff:fix` and `sheriff_fix` report the error count
    before and after, and in the loop the next iteration's analysis does it.
    Never report a repair from the list of fixers that were run.
  Both jar fixers in the image of 21 September are broken, and neither is
  built into it. `BracesForStatements` is registered under a name its own
  `pom.xml` does not produce and declares a main class that does not exist;
  its logic works when launched by hand. `FolderNameUnderscore` is packaged
  correctly but is a stub: it renames a hardcoded `com.oldpkg` to
  `com.newpkg`, never reads the folder it was given, and prints that it
  renamed something and exits 0 regardless. Sheriff also launches every jar
  fixer without `-jar`, so no jar fixer can run at all.
- **`fix -f` with no `--fixers` is not every fixer, nor only the state's.**
  Its default set leaves fixers out, and it sorted PetClinic's imports after
  every `SortedImport` entry had been deleted from `sheriff_errors.json`
  (1 October). With `--fixers A,B` it runs only those, and a code with no
  fixer in the list is ignored. So `sheriff_fix` with no arguments runs the
  default set and then `--fixers` with every rule found, in rounds while the
  count drops: one call, the same end state every time.
- **A bad `--test` profile exits 0 too**, printing a usage banner to stdout
  and the reason to stderr. Locating the findings array by the first `[`
  parses the banner instead.
- **The TypeScript profile traces every checker on stdout**, one
  `COMMAND: node ...` line each, before any findings. A clean TypeScript run
  is only those lines, which read as "not JSON" and failed every clean
  TypeScript component until the analyzer learned to drop them.
- **Every run drops three `sheriff_*.json` files at the root of the mount.**
  They must be cleaned up, except between the `test` and the `fix` of a
  repair — `fix` does not analyze anything, it reads the state that `test`
  left. Two are worth keeping and are read, and optionally exported, before
  the clean-up: `sheriff_errors.json` is **where findings are read from**
  (the same entries as stdout, without the noise; stdout is the fallback),
  and `sheriff_tracked_files.json` gives `AnalysisResult.trackedFiles`, whose
  hashes name what a repair changed (`changedSince`). `sheriff_summary.json`
  has been `{ }` in every run and is ignored. That shared state is why `sheriff:fix` serializes itself across a
  reactor's modules: two repairing at once silently clobber each other.
- **Sheriff is incremental, and that is why its state is cleared *before*
  every run too.** It skips every file whose hash is already in
  `sheriff_tracked_files.json` and reuses what `sheriff_errors.json` says about
  it. But that file holds paths and hashes only: no profile, no rule version.
  Reproduced on 28 September: state left by a `JAVA_HEXAGONAL` run (0 errors)
  made the next `JAVA` run report 0 on code with 3, and the `JAVA` entry it
  recorded was present and empty, so nothing downstream can tell. A new image
  with new rules would be skipped the same way. `SheriffDockerAnalyzerTests`
  pins it (`noStateIsLeftForSheriffToReuse`). Do not keep the state on the
  mount to save time; if the incremental speed-up is ever worth having, keep
  it per component, profile and `image_id`, outside the mount, and measure
  first on a large project.
- **Zero errors means three different things and Sheriff distinguishes none of
  them**: the component complies, it holds nothing of the profile's language,
  or the run produced no report at all. All three are empty output and exit 0.
  The third is not hypothetical — one `sheriff_test` answered "0 errors" for a
  component that has 221, once, unreproducibly. Both the agent and the MCP now
  guard: they check the component for sources of the profile's language, and
  `SheriffDockerAnalyzer`, which every caller goes through, reads Sheriff's
  own `sheriff_errors.json` before cleaning it up. That file records the
  component and profile it analyzed and is the only signal that separates a
  clean run from one that did nothing. There is one analyzer on purpose: the
  MCP used to have its own, and the two drifted.
- **An architecture profile does not include its language's base rules, and
  Sheriff takes one profile per run.** Measured on 29 September: a file with
  five style faults draws five errors under `JAVA` wherever it is, and under
  `JAVA_HEXAGONAL` none in `infrastructure/` or outside the layers (six in
  `domain/model/`, two in `application/service/`). `JAVA_DDD` is the same. So
  `Rules.withBaseProfiles` puts the base profile before every architecture
  profile, wherever a profile comes from (argument, variable, declaration,
  plugin parameter), and the two places that run Sheriff (the analyzer and
  `SheriffRepair`) split the comma-separated list: one
  analysis per profile, merged by `AnalysisResult.merged`, and repairs profile
  by profile, because `fix` reads the state of the `test` just before it.
  Declaring an architecture must add checks, never take the base ones away.
- **Sheriff reports zero errors both when a component passes and when it holds
  nothing of the profile's language.** The two are indistinguishable from its
  output, and in a gate the second is a silent pass. `--check-only` now tells
  them apart itself, by looking for sources of the profile's language, and
  **fails with "Nothing was analyzed"** rather than reporting a pass — the same
  way a component that does not exist already failed. The Maven plugin instead
  *skips* a module with no `src/`, because there an aggregator pom is normal
  and the module list is generated rather than typed by hand.

## The rule catalog is the spine

Sheriff ships no rule documentation. `rules_catalog.json` — 529 rules in the
image of 5 October, 89 with a deterministic fixer, 41 carrying the fixer's own
code — is **extracted from
the image** by the agent:

```bash
cd sheriff-mcp-java && java -jar target/sheriff-mcp.jar --agent --extract-rules
```

Everything else consumes it: the MCP's `sheriff_guidelines`, the agent's
`--rules`, and the deterministic fixer, which reads from it *which* rules it
can repair. A component without the catalog does not fail, and it must not
pretend: `sheriff:fix` once repaired nothing while reporting success, and the
deterministic pass later skipped even the plain `fix -f` (which needs no
catalog) while saying nothing could be repaired. Without a catalog it now runs
the default set and says that only that ran.

**It is not in version control**, and that is deliberate: it is read out of
Kaizten's proprietary image, down to the source of the fixer scripts, so it is
not this repository's content to publish. It is gitignored in both shapes
(`rules_catalog.json` and `SHERIFF_RULES.md`), and nobody has to regenerate
it: every tool that reads it checks it against the image installed and
extracts a new one when it is missing or stale (below); `--extract-rules`
does the same by hand. Everything degrades gracefully
without it, the Dockerfile copies it with a glob so a clone that has not run
the extractor still builds, and the catalog-dependent tests skip themselves
rather than fail.

**The extractor is a workaround, not the design.** Reading rules out of an
image is not something a tool should have to do; it exists because Sheriff's
own export does not carry enough to replace it. `tree --export-profile JSON`
gives `displayName`, `description`, `active`, `settings` and `children`, and
nothing else: no reference code (the id findings carry and `--fixers` takes),
no "has a fixer", no message template, and only 12 of the 35 profiles. Whole
families also share one leaf: by `CatalogBuilder`'s own measurement, going by
the tree alone leaves 105 codes attached to no profile at all, which is why it
merges the tree with the bytecode. If Sheriff ever exports those fields, the
extractor, the bytecode reading, the freshness check and the provisioning at
startup all go away. Push for the export rather than for a better extractor;
meanwhile the extractor stays, because the alternative is having no rules to
show.

There is exactly one copy in this repository, in `sheriff-mcp-java/`; the
Maven plugin's copy is **generated at build time** from that module. Two copies
used to be committed and they drifted apart once, which is the reason the
arrangement is now one file and several readers. `CatalogFreshness` compares
the `image_id` the extractor recorded against the image installed now, and
the plugin's copy is only its first choice: it is unpacked into
`target/sheriff/`, where the agent looks first, and used only while it
matches.

**No tool depends on anyone running the extractor.** `CatalogProvisioning`
decides for all three: a catalog named by hand is used as it is; otherwise the
one beside the tool (the MCP's jar, in a checkout the module itself; the
agent's directory; the plugin's `target/sheriff/`), then
`${XDG_CACHE_HOME:-~/.cache}/sheriff-mcp/`, shared by all three, each only if
its `image_id` matches the image installed. With none, it runs the extractor
(about 6 s), writing atomically so two tools starting together do not
interleave. The MCP server does this in the background from startup, and
again during the session: `ProvisionedCatalog` checks on a lookup, at most
once a minute, so a `docker pull` in another terminal gets its own catalog
while the old one answers. The agent's CLI does it in the foreground for the
modes that read the catalog (not `--check-only`), and `sheriff:fix` before it
repairs. The agent finds the cache only through its own environment
(`XDG_CACHE_HOME`, `HOME`, `USERPROFILE`), so a test built from a bare map
never writes to the real one. That is what lets one jar be dropped into any
project.

The rest of what makes it portable is `ProjectLayout`: the mount and the
component from the working directory (the `src/` rule, same as the hook
script), the profile the project declares or else the one its sources call
for (`.vue` decides Vue; Sheriff's Vue profile also reads `.ts`/`.js`, so
those cannot; `.py` is Python, a profile the image of 5 October checks and
these tools read as nothing to analyze until then), and the test command from
the build tool. `scripts/e2e.sh`
scenarios 8, 9 and 14 exercise it for real. A working directory inside a
module's `src/` is that module, when the module has a build file: Codex names
no project and runs the server and the hooks in its session's directory, and
a session opened in `src/main/java/demo` used to give the server nothing to
analyze and the Stop hook nothing to block.

**A project declares its profile once, and the plugin and the MCP must agree
on where** (`DeclaredProfile`): the `sheriff.profile` property of a
`pom.xml`, read nearest first and only while each directory is a Maven module,
as Maven inherits it; then `profile` in `.sheriff.properties`. Every
`pom.xml` comes before any file, because the Maven build resolves the
property and never sees the file. A declaration applies only to components
in its language, so one at the root of a repository with a TypeScript front
end does not turn that front end into an empty Java analysis, and a
single-module project ignores its parent, which is its siblings' too.
No rule can be taken out. A version that let a project exclude
`SortedImport`, on a model's claim that PetClinic's `spring-javaformat`
rejects Sheriff's import order, was measured and withdrawn: with every Sheriff
fixer applied, PetClinic's build and tests pass. `KnownFalsePositives`, inside
the one analyzer, drops only `FilenamePascalCase` on `package-info.java` and
`module-info.java`, names Java requires.

**The MCP's answers decide the next step, not the model.** Every answer of
`sheriff_test` and `sheriff_fix` ends with one `Next step:` (`NextStep`),
chosen from the state alone and with no alternative in it, and the server's
instructions are to do that step. Measured on 1 and 5 October: the answers
were the same for the same code call after call, and the work still differed
by model, because "fix these or ask for a narrower component" and "call the
fixer with one of these codes" left the choice to it. Do not add wording that
offers the model a choice; `ToolsTest` checks the step has none, and that it is
the answer's last line (`NextStep.last` moves it below the task's folder and
the image notice). The work is one loop with one tool: `sheriff_fix`, what it
leaves by hand, `sheriff_fix` again; after an analysis the step is the repair
whatever the catalog says, so it does not change while one is extracted. The
test command a step names is `ProjectLayout.testCommandForModel`: absolute,
because the server's own is relative to the mount and a model's shell is in
the project, and without `-q`. What is left by hand is listed bottom-up per
file and followed by the Java Sheriff accepts for it (`AcceptedCode`, from
`src/main/resources/accepted-code.md`): change a shape there only after
checking it against the image, as every one in it was.

**The hooks speak the same language.** A hook has already analyzed, so it
ends with the step an analysis ends with, from `ComponentGate.repairStep`: the
`sheriff_fix` tool with the component named, what done looks like ("Next step:
none, the component is done"), and the command for a session with no sheriff
tools, `--call`, which goes through the same `Tools` and so answers the same,
its steps written as commands (`ToolCall`). Every step names the component: "call
sheriff_fix with no arguments" failed in a repository of several modules.
`scripts/e2e.sh` runs the command a Stop hook printed, as it stands.

**The answers are as short as the work allows, and that is measured, not
guessed** (task records under `~/.local/state/sheriff-mcp/tasks/` hold every
answer of every session): `sheriff_test` answers with counts
(`FindingsRenderer.summary`), as its step is the repair, whose list writes the
shared folder once, one line per error and a rule's `howToSolve` once, and only
for a rule `accepted-code.md` does not show. That list is whole files, in path
order, until they hold `SheriffFixTool.ERRORS_PER_ANSWER` (20) errors, with the
rest counted per file, and the step is those files and no other
(`NextStep.afterRepair`): the whole list was 8 to 11 KB a call, and Codex,
fixing one file per call, got it fifteen times; one file per answer would be a
call for every file of one error. `AnswerMemory` says when a list is the same
as the last one. On PetClinic a Codex session made 20 calls for
238 KB of answers, three of them the same 172 errors. Do not point an answer
elsewhere for the rest: "the rest are in that answer", and the task's folder
named in every answer, sent the next Codex session to read Sheriff's raw JSON
(100 KB) instead of fixing the file; answers name the task's id only. A
component that is a path inside one is refused before Sheriff runs, naming the
component (`ToolContext.componentArgument`). Its description gives no example
name: Codex called `component: "backend"`, the one it used to give.

**A clean component is not finished while a method the change added has no
test** (`UntestedMethods`): a non-private method with a body in `src/main`
that the last commit does not have must be called from a test file that
changed too. The MCP's clean step names them, and the Stop hook blocks on
them before the tests. It reads Java declarations by pattern, not by parsing,
and compares with `git show HEAD:`; with no repository it checks nothing.
`scripts/e2e.sh` scenario 19 runs it against real git and Sheriff.

`sheriff_fix`, the loop's first pass and `sheriff:fix` repair with one class,
`SheriffRepair` (`SheriffRunner.fix` and `DeterministicFixer` only call it
and word its outcome): Sheriff's default set, then `--fixers` with every rule
found, again for each profile whose count the round before lowered, up to
three rounds, each round's analysis measuring the one before; a `fix` that
did not run is a failure, not a repair that found nothing. It used to be
written twice, and the copies had drifted (flags, rounds, a dead `fix`
noticed by one and not the other); `DeterministicFixerTests` now checks the
loop and `sheriff_fix` give Sheriff the same commands.

## Architecture

The agent is hexagonal and **the layering is enforced, not trusted**:
`LayeringTests` fails on any import a layer is not explicitly allowed —
dependencies point inwards, the domain knows nothing outside itself, and only
infrastructure touches I/O or a third-party library. Adding a dependency to
`domain/` or `application/` fails the build with the offending import named,
and so does a source outside the three layers.

That is what makes the adapters cheap: **the CLI is a driving adapter, the
MCP server is another (`infrastructure/mcp/`), and the Maven Mojo is a third,
in its own module.** The plugin adds the Maven-facing edge and
the decision a build takes from a result; the analysis, the Docker call and
the value objects are the agent's, unchanged.

`ProcessRunner` is the seam everything external goes through, so the exact
`docker` and `claude` invocations are asserted against recorded values rather
than inspected on a mock.

**It runs on Linux, macOS and Windows, and CI builds on all three.** What
differs lives in `Platform`, one place: `cmd.exe /c` instead of `/bin/sh -c`
for the project's tests (with `mvnw.cmd`, double quotes), paths compared with
Sheriff's and git's always written with `/`, Claude Code rules in its POSIX
form (`C:\x` as `//c/x`), and executables such as npm's `claude.cmd` found on
the `PATH`. With `SHERIFF_DOCKER=wsl` (or `wsl:<distro>`; `install.ps1
-DockerWsl` sets it for the user) every `docker` command becomes `wsl.exe
docker`, a bind mount's `source=C:\x` written `/mnt/c/x`, for a Windows
machine whose Docker is the engine inside WSL rather than Docker Desktop. The first Windows build found git paths with backslashes, which
made every pass look out of scope; do not compare `Path.toString()` with a
path Sheriff or git wrote. The end-to-end job stays on Linux: GitHub's macOS
and Windows runners have no Docker for Linux containers.

**Sheriff's image is managed, not assumed** (`ImageProvisioning`). Missing, it
is pulled with its own time limit (60 min, not the analysis's 300 s): in the
background from the MCP server's start, with the tools answering "being
downloaded" meanwhile and the catalog extracted after it; in the foreground
from `--check`, the agent's CLI and the Maven goals. The hooks never pull:
with no image they let the edit through at once. Out of date (Docker Hub's
digest for the tag differs from the local one) it is reported, once per
session, and replaced only with `SHERIFF_PULL=always`: new rules should not
arrive in the middle of someone's work unasked.

## Rules this repository holds itself to

Kaizten's rules are not a convention to interpret — they are compiled into the
image as profiles. Two consequences when writing code here:

- **Both modules must stay green under `JAVA`.** Check before finishing, and
  prefer the plugin's own goal for the plugin. Both were green as of the last
  change.
- **JavaDoc is at least three lines** (`/**`, text, `*/`, each on its own
  line) on every method, constructor and field, with `@param` and `@return`.
  No hardcoded strings or numbers in a call, braces on every branch, imports
  in one sorted block, no blank lines or comments inside a method body.
- **Value objects in `domain/valueobject/` need an Object Mother**, and the
  rules are opt-in *by folder*: the same class draws nothing under
  `domain/model/` and several findings under `domain/valueobject/`.

`JAVA_HEXAGONAL` and `JAVA_DDD` each report exactly one error on
`sheriff-mcp-java`:
`domain/port` is not an allowed domain directory name. That is known and
deliberate — in hexagonal architecture the ports *are* domain, so the rule and
the architecture disagree about one directory. Do not "fix" it by renaming.

## When running the loop

Six backends, and the choice only decides *who answers* — the loop, the
batching, the scope check and the commits are identical for all of them:
`claude_cli` (default, uses the subscription, no key), `codex_cli`,
`antigravity_cli`, `anthropic_api`, `openai_api`, and `local`.

**`openai_api` and `local` are one adapter.** Ollama, LM Studio, vLLM and
llama.cpp all speak OpenAI's `/chat/completions`, so the difference is a base
URL (`OPENAI_BASE_URL`) and whether a key is required. It uses the JDK's HTTP
client, not an SDK, and adds no dependency. Both API backends work by offering
`read_file`/`write_file`, and **how well that goes is a property of the model,
not of the loop**: a model that cannot emit structured `tool_calls` never calls
one, and one that can may still call `read_file` and stop without writing —
both observed on the same local model across two runs of the same file. Either
way nothing unintended happens; the loop parks the file it made no progress on
and cuts. **Both tools are kept to the component** (`FileTools`, given it by
`Composition`), reading as much as writing, with symbolic links followed to
where they lead: paths stay relative to the mount, but for a single-module
project the mount is the folder of every other project beside it, and the
model could read their `.env` files and, in the repair pass, write into them
where no `git status` of the component would show it.

`AI_BACKEND=claude_cli` (the default) uses the Claude Code subscription and
needs no API key. The loop works on its own branch, commits each pass, and
**cuts the run without committing when the model touches a file outside its
batch** — that is a scope violation, not a failure of the fix, and the work is
left in the tree for review.

The order inside a run matters and is tested: branch first, then Sheriff's
own fixers (`AutomaticRepair`, committed on their own), then the model
passes. Git runs from inside the component and translates its paths to the
mount's, so a repository that *is* the module gets the same branch, commits
and scope check; no repository, or any git command failing, stops the run
loudly. The assistant the loop launches carries `SHERIFF_AGENT_RUNNING=1`,
and the hooks stand aside for it. With `claude_cli` it may edit only inside
the component, by Claude Code's own rule `Edit(//<absolute component path>/**)` rather than
`--permission-mode acceptEdits`: the CLI runs in the mount, which for a
single-module repository is its parent, and acceptEdits made every sibling
project editable. Validated with a real run: 15 errors to 0, tests green,
no permission denied, the sibling project untouched. **The path in that rule
must be absolute**: Claude Code resolves a relative `Edit(<component>/**)`
against the session's *current* directory, so a model that runs
`cd <component>` before editing is denied every edit after it. That made
runs fail intermittently until 28 September; `ClaudeCliFixerTests` pins the
absolute form. It may also run the project's test command, as an exact
`Bash(<command>)` rule without the `cd`: without it a repair pass worked
blind, fixing the one error the output showed, while Codex, which runs
commands, reached green on the same project. `codex_cli` gets the
same confinement another way: it runs with `--cd <component>`, which is also
what lets it start at all in a single-module layout, since it refuses to run
outside a git repository. Validated for real too (15 to 0 in two passes).

Antigravity is also an MCP client (`agy` 1.3.1, tried 7 October): it reads
the server's instructions and follows the steps, but has no hooks.
`--install-antigravity` (`AntigravityInstaller`, which `install.sh` runs when
`agy` is on the PATH) writes the server where `agy mcp add` does,
`~/.gemini/config/mcp_config.json`, and allows the four tools that do not commit
in `~/.gemini/antigravity-cli/settings.json`: its headless mode refuses any
tool it cannot ask about, MCP tools included, unless `permissions.allow` names
it (`mcp(sheriff/sheriff_test)`). `agy` follows `HOME`, so a trial can run with
a home of its own, as `AntigravityHome` does; the jar does not, so pass it
`-Duser.home` too.

`antigravity_cli` runs Google Antigravity's `agy` inside the component with
`--mode accept-edits`, the prompt on standard input (`-p` takes it as an
argument; with no `-p` and a stdin that is not a terminal, `agy` reads it
there). Measured with `agy` 1.1.16, on 7 October: without its interface every
write is refused, `accept-edits` accepts the ones inside the directory it runs
in and refuses those outside, and every terminal command, URL or tool but
reading and editing is refused, the refusal ending the session with exit 0
and no output. **Measure it outside `/tmp`:** there `agy` writes anywhere,
which made a first test say it was not confined at all. Its permissions come
only from `~/.gemini/antigravity-cli/settings.json`, with no flag, and its
login from the system's keyring, so each pass runs with a `HOME` of its own
(`AntigravityHome`): a copy of the user's settings with `command(<test
command>)` and `command(git mv)` added, deleted after, the user's file never
written. `command(X)` allows `X` with more arguments and refuses anything
chained to it (`&&`, `;`, `$(...)`, a pipe, a redirection), measured. The
commands it runs inherit that `HOME`: Maven and Gradle find their caches
through Java's `user.home`, which ignores it, and `MAVEN_USER_HOME` and npm's
two variables point the rest back (`Configuration.buildToolHomes`). On
Windows, where `agy` reads `USERPROFILE`, none of this has been tried, and it
runs with no command. The prompt names the two commands as the only ones, a
session cut at a refused tool anyway is reported rather than failed (a failed
fixer stops the run), and `--print-timeout` is set to the fixer's own limit,
as `agy` gives up after five minutes. Validated on PetClinic, 7 October.
Without the tests it got 221 errors to 0 and then could not pass
`spring-javaformat:validate` (two JavaDoc lines wrapped differently), and its
repair pass, unable to run the build, reached for a URL and was cut. With
them, the same run reached 0 in five passes with the 81 tests and the
formatter green and no repair pass, having run `./mvnw -q test` itself.

**The iteration cap grows while the passes make progress**, unless one was
given (`--max-iterations`, `max_iterations` in `sheriff_autofix`). The first
analysis sizes it as one pass per batch of files plus two to spare
(`BatchPolicy.estimateMaxIterations`), and every pass that lowers the count
re-sizes it from the files still with errors (`extendedMaxIterations`), up to
three times the first estimate. A project analyzed for the first time, with
errors in hundreds of files and many needing a second pass, used to stop at
`iterations_exhausted` with work left after the two spare passes. A pass that
lowers nothing never grows it: its files are parked, and the run stops when
nothing is left to try.

Standards clean is not the same as working: Sheriff's fixers and a partial
pass can break the build. So the loop runs the project's tests however it
stops (`RunSummary.testsPassed`), `sheriff_fix` takes `verify`, the Stop
hook requires green tests with `SHERIFF_STOP_RUNS_TESTS=1`, and `sheriff:fix`
analyzes again and fails on what it could not repair.

Two more things the loop and the tools rely on. The prompt reaches
`claude -p` on standard input, not as an argument: Linux caps one argument at
128 KB, and a batch of large files passes that. And every Sheriff run holds
`MountLock` for its mount, across threads and processes, with the whole
`test`, `fix`, `test` sequence held at once: Sheriff keeps its state at the
root of the mount, so two sibling single-module projects checked together
used to clobber each other's. The lock files, and the markers the hooks
leave for their next run (gate, turn, stop), live in a folder of the
temporary directory per user (`TemporaryFolders`): shared, the first user to
create one left it unwritable for the others. Markers older than a week are
pruned when their folder is written; lock files never are.

The MCP server writes the protocol to the stream it claims at startup and
points `System.out` at stderr. Anything in the core that prints (the loop
does) must never reach the JSON-RPC channel. `--agent` is dispatched before
that, so the agent's CLI keeps its stdout. A `tools/call` runs on a thread of
its own, one at a time in the order they came, so a `ping` or a `tools/list`
is answered while Sheriff or the tests run; responses are written whole, and
when the input ends the calls already read are answered first. Background
tasks run on one daemon thread, one at a time; when the client disconnects the server waits for them
to finish rather than cut a loop mid-pass. A client that stops the server
instead gets no such courtesy: Codex ends a session with SIGTERM to the whole
process group and SIGKILL about 0.3 s later. A shutdown hook then records
every unfinished task as `interrupted`, and nothing may overwrite that record
afterwards (the loop, whose `git` died with it, used to record a failure over
it). A background task's failure also waits 0.2 s before it is recorded, as
that `git` can die before the hook starts, and one run in five used to end
`failed`; a task still queued when the stop comes never starts. Task records
are written to a file of their own and moved into place, because a JVM halted
mid-write left an empty `task.json`.

`mvn -q test` is the wrong verification command: `-q` suppresses Maven's INFO
lines, which is where `Tests run:` and `BUILD SUCCESS` are.
