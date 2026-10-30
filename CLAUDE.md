# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

It holds what is true now and the reason for it in a line. The history of
how each rule was found (dates, earlier forms, what failed) belongs in the
commit that changed it, not here.

## What this repository is

Two modules around **Sheriff** (`kaizten/sheriff:latest`), Kaizten's code
standards analyzer, which is a Docker image with a CLI and no library
interface. Everything here is a different shape of reaching it:

| | |
|---|---|
| `sheriff-mcp-java/` | **the product**: Sheriff as six MCP tools, so a model can reach it while writing code — one jar, registered once, that works in any project with no configuration. **The agent lives inside it**: the loop that fixes code until Sheriff is quiet, its hexagonal core (`domain/`, `application/`, `infrastructure/`), the rule-catalog extractor, and its CLI, reached as `sheriff-mcp.jar --agent`. The server is a driving adapter in `infrastructure/mcp/`, beside the CLI in `infrastructure/cli/`. The same jar is the CI gate (`--check`) and the three Claude Code hooks (`--hook-gate`, `--hook-stop` and `--hook-turn`, wired for every project by `scripts/install.sh`, or for one by `--install-hooks`), and sets Codex up (`--install-codex`). It hands the client its order of work as MCP instructions (`src/main/resources/instructions.md`) |
| `sheriff-maven-plugin/` | the same check as a Maven goal, bound to `package` (so `mvn package` runs it), and `sheriff:fix`, which fails when errors are left |

**The interaction is** developer → assistant (with skills) → MCP server →
agent → Sheriff, whose output is JSON. That is why the agent is inside the
MCP module rather than beside it, and why the server works in tasks:
`sheriff_autofix` answers at once with a task id and runs in the background,
`sheriff_task` reports id, command and state, and every call that runs
Sheriff is recorded as one, under
`${XDG_STATE_HOME:-~/.local/state}/sheriff-mcp/tasks/<id>/`. Each session
prunes once, on its first task, keeping the newest
`TaskRegistry.RETAINED_FOLDERS` and only touching id-shaped folders that hold
a `task.json`.

The rules-only skill is Sheriff's own
(`ai --skill --provider ANTHROPIC -t <profile>`), not this repository's.

**Documentation is kept to two files**: `README.md` (install, use,
configuration) and this one. Do not add module readmes or plans; put a fact
in the one whose reader needs it. `AGENTS.md`, which Codex reads, only sends
it here: a translated copy drifts. The third reader is the person who
installs a release: `playbook/`, the user's playbook in Spanish and English
(LaTeX, one style and one `.tex` per language), built by `scripts/playbook.sh`
and attached to every release as `sheriff-playbook-es.pdf` and
`sheriff-playbook-en.pdf`. It holds plays, not reasons. A change to what a
user types or reads (the install line, an option, a tool's answer, a hook's
message) goes into both languages in the same change, and its answers are
copied from a real run. It must build with only the TeX packages
`scripts/playbook.sh --tex-packages` names, which both workflows install on
Ubuntu (not `pifont`); `playbook.yml` checks that on the pull request.
Installing TeX is two of a build's three minutes, so both workflows cache
the two PDF files under `playbook.sh --key` (both versions and a hash of the
sources) and skip TeX when they find them; the release finds the ones main
built for its version, and their cover carries that day's date.

The fourth reader is Sheriff's own team, who can change anything in the
image: `docs/sheriff/`, one request per document, in Spanish. **It is
gitignored and never published**, so it exists only on the machine that
wrote it: never commit it, push it, or put its content in a pull request,
and do not move it back into the README. A new defence against something
Sheriff does gets its request there, measured, with the image it was
measured on.

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
scripts/benchmark.sh                          # the loop over public projects, measured: spends tokens
```

`scripts/e2e.sh` checks what the unit suites cannot see without Docker:
which branch the loop edits, whether the scope check sees a
module-that-is-a-repository, whether the MCP's stdout is clean, whether a
timed-out container is really gone. A stand-in `claude` plays the model, so
it costs nothing. Every scenario in it was a real bug; run it after touching
the loop, git, the hooks, the MCP entry point or how containers are launched.

**Run `scripts/ci-local.sh` before pushing, and push only when it passes.**
It reads the steps out of `ci.yml` by name and runs them as written, in a
HOME, a Java `user.home` and a PATH of their own (with the real `claude` the
installers would set this machine up), plus `install.ps1` in PowerShell 7 in
a container, `install.sh` under bash 3.2, the workflows' syntax and the
playbook. It cannot reach macOS, which only GitHub tries, nor Windows
PowerShell 5.1, cmd, Git Bash or winget, which nothing tries while Windows is
out of the workflows; it says so. A new CI step
that can run on Linux belongs in it too.

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
mvn com.kaizten:sheriff-maven-plugin:0.1.10:check    # on any Maven module
```

`mvn -q test` is the wrong verification command: `-q` suppresses the lines
holding `Tests run:` and `BUILD SUCCESS`. No test count is stated here or in
the readmes, as every stated one drifted; read `Tests run:` from the build.

The agent is configured entirely by environment variables — `TARGET_REPO`,
`SHERIFF_COMPONENT`, `SHERIFF_TEST_TYPE`, `AI_BACKEND` — because it is also
launched as a hook and as an MCP-less CLI. The README has the table,
including `SHERIFF_EXPORT_DIR`.

## Installing

`scripts/install.sh` copies the runnable jar and the catalog into
`~/.local/share/sheriff-agent/` and **sets up only the assistants the machine
already has**, never installing one, then says in its last line which. With
no assistant it writes nothing into anyone's configuration and says the jar
is installed only as the CI gate and the Maven plugin. `install.ps1` mirrors
it. A command is looked for on the PATH and then where npm, nvm, Homebrew and
the assistants' installers put it (`find_command`, `Find-Assistant`), as an assistant installed
is not always on the PATH the installer is given. One found off the
PATH is set up all the same, and named with the line that puts it on the
user's, but for a folder of one of nvm's Node versions, which that line would
pin; one not found gets the command that sets it up without looking.

- **Claude Code** (a `claude` command, or `~/.claude`, which the desktop
  app and the IDE extensions read without putting `claude` on the PATH): the
  MCP server at user scope as `sheriff` when there is a `claude` to register
  it with, and the hooks in `~/.claude/settings.json`
  (`--install-hooks --user`), with `permissions.allow` rules for the four
  tools that do not commit or spend tokens (not `sheriff_autofix`), so a
  model without auto mode is not stopped at a prompt. `--uninstall-hooks`
  takes out only those rules.
- **Codex** (a `codex` command, or `${CODEX_HOME:-~/.codex}`, as setting it
  up only writes there; `--install-codex`, `CodexInstaller`): the
  server in `~/.codex/config.toml` with the time limit and the approval
  `codex mcp add` cannot write, the order of work in `~/.codex/AGENTS.md`,
  and the Stop and turn hooks, without the gate, in `~/.codex/hooks.json`.
  Codex's review of a new hook is left to the user on purpose; the installer
  and the playbook say what skipping it costs (without the Stop hook Codex
  stops with errors left).
- **Antigravity** (an `agy` command, `--install-antigravity`): see below.

**Codex runs a hook in Windows PowerShell 5.1**, not cmd, so on Windows each
hook line is PowerShell's: `& 'C:/.../java.exe' '-Dsheriff.docker=wsl' -jar
'...' --hook-stop; exit $LASTEXITCODE`. The `&` runs a quoted path, single
quotes keep a `$` in a path, the property is quoted because PowerShell 5.1
splits `-Da.b=c` at its dot, and without the `exit` a Stop hook's 2 reaches
Codex as a failure, not a block. **Never put a `set` on a hook's line**: in
PowerShell a `set "` reached cmd as a bare `set`, which printed every
variable of the user's, and Codex handed that to the model.

Options: `--no-hooks`, `--no-codex`, `--no-antigravity`; `--fail-fast` writes
the hooks as `--hook-stop --fail-fast` (an option, not a variable, as Codex
passes a hook almost none of the shell's); `--pull-always` writes
`SHERIFF_PULL=always` into the server's registration in both assistants,
which is the default now and is kept so older install lines are not refused;
`--no-mcp`, which CI uses with `--no-pull`, touches no assistant's
configuration at all. An option the jar does not know is refused with exit
code 2.

Every installer writes an assistant's file through `ConfigFile`: whole,
where a symbolic link points, and **with the permissions it had**, since
these files can hold tokens. Claude Code's settings go out in Claude Code's
own format (`ConfigFile.json`), so a reinstall changes only its own lines.
The server, and the hooks written for the user, run the Java the installer
found, by its absolute path (`SHERIFF_JAVA`, read
by `--install-hooks --user`, `--install-codex` and `--install-antigravity`,
and given to `claude mcp add`): an editor started from the desktop can have
another PATH, and Claude Code runs hooks in Git Bash, where a JDK installed
without its PATH entry is not found. The installers look in `JAVA_HOME`, then
the PATH, then where JDKs are installed; a Java update that removes the old
folder needs the install run again, which the playbook says. With no Java,
`install.ps1` installs Temurin 21 with winget. A project's own
`.claude/settings.json`, which a team commits, keeps `java`. Both installers
end by starting the server as registered and sending it `initialize`, and
fail unless `serverInfo.name` is `sheriff`.

**On Windows a running server holds its jar.** Windows refuses to delete or
rename it, so the installers overwrite it in place (not `install`), and a
server whose jar changes under it dies at the next class it loads.
`JsonRpcServer.answer` answers any `Error`, a `LinkageError` with "cannot
load its own code ... Restart this session", so the call does not hang. Both
installers check first whether anything holds the jar and then say open
sessions must be restarted. Under Git Bash, `install.sh` writes paths as
`C:/x` with Git's own `/usr/bin/cygpath -m` (conda's comes first on the PATH
when its base environment is active, and gets it wrong).

**The same script installs a release**: read from a pipe, or with
`--release[=TAG]`, it has no checkout, so it downloads the release's jar and
checksum by their public URLs (`releases/latest/download/`, or
`releases/download/<tag>/`) with curl or wget, no login. Those URLs never
name a prerelease, and `release.yml` runs the line with no token to keep it
so. Everything runs from `main()` on its last line, so bash has read the
whole script before any of it runs, and nothing in it reads standard input,
which in `curl | bash` is the script itself. By default it then pulls
Sheriff's image; `--install-docker` installs Docker from Docker itself
(`get.docker.com`, `pacman` on Arch, Docker Desktop's installer on macOS and,
per user, on Windows) and never accepts Docker Desktop's terms for the user.

`install.ps1` has rules of its own: it never calls `exit` (run as a script
block it closes the window); it is ASCII only (GitHub serves it as
`application/octet-stream`, with no charset); it runs as a script block, not
`irm | iex`, so its variables and `$ErrorActionPreference` stay out of the
user's session; and native commands run for an exit code go through
`Test-Native`, as Windows PowerShell turns a redirected stderr into errors.

**Changing `HOME` does not keep a trial install off the real
configuration.** `claude` follows `HOME`, but the jar finds the home through
Java's `user.home`. Add `JAVA_TOOL_OPTIONS=-Duser.home=<dir>` (and
`CODEX_HOME`), use a container, or install with `--no-mcp` and
`SHERIFF_HOME`, as CI does.

The install folder is what the hooks point at, and a jar there resolves its
catalog and logs beside itself. A registration pointing into `target/`, or at
a module that has been removed, fails at session start as "connection
closed"; re-run the script after pulling.

## CI and releases

`.github/workflows/ci.yml` runs on every push to `main` and every pull
request: `mvn -B install`, then `scripts/e2e.sh` against the pulled image (a
red e2e with a green build can be a new image rather than the code). On Linux
and macOS it also runs the installer as a user would: with no assistant, then
with stand-in `claude`, `codex` and `agy` that only record their calls, under
`/bin/bash` (3.2 on macOS). **Windows is out of both matrices** (`ci.yml`
and `release.yml`) while the product is not meant for it, to keep the runs
short; its code and its steps (Git Bash, Windows PowerShell, Java on no PATH,
winget) stay, and putting `windows-latest` back in the matrix runs them
again. `scripts/ci-local.sh` still runs `install.ps1` in PowerShell 7.

`image.yml` publishes `ghcr.io/kaizten/sheriff-mcp-server` when CI passes on
`main`, only builds it on a pull request, and fails rather than publish the
rule catalog (the Dockerfile copies one that sits beside it).

`release.yml` publishes a release when a tag `v<version>` is pushed: it fails
unless the tag is the version the jar reports and the jar is free of the
catalog, and then runs the install line on Linux and macOS. A tag with a
suffix is a prerelease, which the install line never picks: that is how a
release is tried first, and on Windows, which no workflow reaches now, it has
to be tried by hand.

## Things about Sheriff that will waste your time otherwise

- **Sheriff analyzes a *subdirectory* of what it mounts.** So a component is
  always "the parent and the directory's name", and **nested components
  (`a/b`) are rejected**. This is the single most common source of confusion
  here, and it is why the Maven plugin needs no configuration: Maven already
  knows both halves. `SheriffContainer` does not mount the parent: it mounts a
  state folder of the component's own at `/data` and the component alone at
  `/data/<component>`, so Sheriff's arguments and paths are unchanged but it
  sees nothing beside the component. With the parent mounted, a neighbour's
  `domain/` folder changed what `JAVA_HEXAGONAL` reported for a component
  (masked through the tools by the base profile and `KnownFalsePositives`, but
  not by design), and a single-module project's parent got Sheriff's
  root-owned state files. Its time grows with what it mounts, and through
  WSL's Docker from `/mnt/c` by orders of magnitude (seconds on WSL's own
  disk, minutes from `C:`, past `SHERIFF_TIMEOUT`'s 300 s); whether mounting
  the component alone shortens the `C:` case has not been measured.
- **Sheriff analyzes every file of the component, what its build wrote
  included, and cannot be told to leave a folder out.** After a Gradle build
  almost all of a project's errors can be in `build/generated`. So
  `SheriffContainer` covers, inside the container, `target/` beside a
  `pom.xml` and `build/` and `.gradle/` beside a `build.gradle` with an empty
  `tmpfs`: Sheriff neither reads nor repairs them, and nothing on the disk
  changes. It goes by the build file, not by name alone, since a `build/` may
  be a project's own source. `scripts/e2e.sh` scenario 20 checks it.
- **`test` exits 0 however many errors it found**, and with a profile it does
  not know; only a component that does not exist exits 1. `fix` used to exit
  1 whatever happened; with the image of 16 October (`sha256:4127439f`) it
  exits 0 when it runs and 1 when it cannot start. Decide what happened from
  the output, or by analyzing again.
- **A fixer that cannot run looks like one that changed nothing**, and one
  that lies looks like one that worked. Two defences, both needed:
  - A fixer that *fails* says so on stdout: `Could not apply fixer for error
    'CODE'`, then `Error: <reason>`. `FixerFailures` reads those lines and
    every report of a repair names them.
  - A fixer that *exits 0 having done nothing* says nothing. Only analyzing
    again catches it, so a repair is always followed by another analysis.
    Never report a repair from the list of fixers that were run, nor from
    a finding's status in `fix --report`'s JSON: a fixer that clears every
    finding of its rule in a file marks one `fixed` and the rest
    `unattempted` (4 of 26 `EmptyLinesInMethod` on PetClinic, image of 29
    October 17:29, `sha256:05a1f4a4`). That image's `remaining` is an
    analysis (`remainingVerified`; 1,432 and 1,406 in two rounds, the same
    as a `test` after each), and `--dry-run` says `planned` or `skipped`
    per finding, but plans every finding of a file where one run fixes only
    the first rule (28 planned, 2 fixed).
  Of the image's two jar fixers, `BracesForStatements` works (images of 28
  and 29 October: it braces a classic `for`), and `FolderNameUnderscore`
  renames `my_pkg` to `my-pkg` and leaves `package com.my_pkg;` as it was
  (`sha256:05a1f4a4`): no Java package can be named after that folder, and
  the analysis after it finds nothing.
- **`fix -f` with no `--fixers` is not every fixer, nor only the state's.**
  Its default set leaves fixers out and runs some regardless of the state.
  With `--fixers A,B` it runs only those. So `sheriff_fix` with no arguments
  runs the default set and then `--fixers` with every rule found, in rounds
  while the count drops: one call, the same end state every time.
- **A bad `--test` profile exits 0 too**, printing a usage banner to stdout
  and the reason to stderr. Locating the findings array by the first `[`
  parses the banner instead. So does a `fix` argument it refuses, among
  them `--type DETERMINISTIC` as its help lists it (image of 26 October):
  `SheriffRepair` passes `--type deterministic`, lower case, as `fix` would
  run its AI fixer too with credentials within reach, and a `fix` that
  answers with the banner did not run. With `sha256:05a1f4a4` a `fix` with
  no `--type` does the same, as `all` needs `--ai-provider`.
- **The TypeScript profile traces every checker on stdout**, one
  `COMMAND: node ...` line each, before any findings; the analyzer drops
  them, or a clean TypeScript run reads as "not JSON".
- **Sheriff finds its TypeScript analyzer in `./node_modules`**, under `/`,
  so `SheriffContainer` runs it with `--workdir /`. The image of 29 October
  (`sha256:e03d9daf`) declares `WORKDIR /data`, and there every TypeScript
  analysis reported 0 errors and exited 0, Node's "Cannot find module" on
  stdout; `scripts/e2e.sh` scenarios 9 and 10 catch it.
- **Every run drops three `sheriff_*.json` files at the root of the mount**,
  which is the component's state folder,
  `${XDG_CACHE_HOME:-~/.cache}/sheriff-mcp/state/<digest of its path>`
  (`sheriff.state.home` in the tests), never the project. Docker runs there,
  and leaves in it the empty root-owned folder it mounted the component on;
  folders no run has used for a week are pruned. Not in `/tmp`: Docker as a
  snap has a `/tmp` of its own. They must be cleaned up, except between the
  `test` and the `fix` of a repair — `fix` analyzes nothing, it reads the
  state `test` left. Before the
  clean-up, `sheriff_errors.json` is **where findings are read from** (stdout
  is the fallback) and `sheriff_tracked_files.json` gives
  `AnalysisResult.trackedFiles`, whose hashes name what a repair changed
  (`changedSince`); both can be exported. `sheriff_summary.json` is always
  `{ }` and ignored. Newer images also leave `sheriff_history.json` (every
  run appended) and, since 29 October, `sheriff_tracked_components.json` (a
  hash per component); they stay in the state folder, as a run with them
  kept found the same errors (measured).
- **Sheriff is incremental, so its state is cleared *before* every run too.**
  It skips every file whose hash is in `sheriff_tracked_files.json`, which
  records no profile and no rule version: state left by one profile makes the
  next profile, or a new image, report 0 on code with errors.
  `SheriffDockerAnalyzerTests` pins it (`noStateIsLeftForSheriffToReuse`).
  Do not keep the state on the mount to save time; if the speed-up is ever
  worth having, keep it per component, profile and `image_id`, outside the
  mount, and measure first.
- **Zero errors means three different things and Sheriff distinguishes none
  of them**: the component complies, it holds nothing of the profile's
  language, or the run produced no report at all. So every caller checks the
  component for sources of the profile's language, and
  `SheriffDockerAnalyzer`, the one analyzer every caller goes through, reads
  `sheriff_errors.json`, which records the component and profile analyzed, to
  tell a clean run from one that did nothing. `--check-only` **fails with
  "Nothing was analyzed"** rather than pass; the Maven plugin instead *skips*
  a module with no `src/`, as an aggregator pom is normal there.
- **An architecture profile does not include its language's base rules, and
  Sheriff takes one profile per run.** A file with style faults draws them
  under `JAVA` and few or none under `JAVA_HEXAGONAL` or `JAVA_DDD`. So
  `Rules.withBaseProfiles` puts the base profile before every architecture
  profile, wherever a profile comes from, and the two places that run Sheriff
  (the analyzer and `SheriffRepair`) split the list: one analysis per
  profile, merged by `AnalysisResult.merged`, and repairs profile by profile,
  because `fix` reads the state of the `test` just before it. Declaring an
  architecture must add checks, never take the base ones away.

- **The image has a `metrics` command** (`--coverage`, `--mutation`,
  `--crap`, `--complexity`; image of 16 October), which this repository does
  not use. It refuses to start without `--token`, a GitHub Packages token it
  writes into a Maven `settings.xml` and an `.npmrc` for Kaizten's private
  packages; it measures whole components, not a change; its mutation runs the
  PIT a project's `pom.xml` configures, found through `help:effective-pom`;
  and TypeScript coverage needs a `test:coverage` script that passes
  `--coverage`. Read from its classes, not run: no token was used.
  `ChangeCoverage` needs none of that, which is why it is separate.

## The rule catalog is the spine

Sheriff ships no rule documentation. `rules_catalog.json` (about 530 rules,
under a hundred with a deterministic fixer) is **extracted from the image**
by the agent:

```bash
cd sheriff-mcp-java && java -jar target/sheriff-mcp.jar --agent --extract-rules
```

Everything else consumes it: the MCP's `sheriff_guidelines`, the agent's
`--rules`, and the deterministic fixer, which reads from it *which* rules it
can repair. A component without the catalog does not fail, and must not
pretend: without it the repair runs Sheriff's default set and says that only
that ran.

**It is not in version control**, on purpose: it is read out of Kaizten's
proprietary image, down to the source of the fixer scripts. It is gitignored
in both shapes (`rules_catalog.json` and `SHERIFF_RULES.md`). Everything
degrades gracefully without it, the Dockerfile copies it with a glob so a
clone without it still builds, and the catalog-dependent tests skip
themselves.

**The extractor is a workaround, not the design.** Sheriff's own
`tree --export-profile JSON` gives `displayName`, `description`, `active`,
`settings` and `children` only: no reference code (the id findings carry and
`--fixers` takes), no "has a fixer", no message template, and only some of
the profiles; whole families share one leaf, so the tree alone leaves codes
attached to no profile, which is why `CatalogBuilder` merges it with the
bytecode. If Sheriff ever exports those fields, the extractor, the bytecode
reading, the freshness check and the provisioning all go away. Push for the
export rather than for a better extractor.

There is one copy, in `sheriff-mcp-java/`; the Maven plugin's is
**generated at build time** from it. **No tool depends on anyone running the
extractor.** `CatalogProvisioning` decides for all three: a catalog named by
hand is used as it is; otherwise the one beside the tool (the MCP's jar, the
agent's directory, the plugin's `target/sheriff/`), then
`${XDG_CACHE_HOME:-~/.cache}/sheriff-mcp/`, each only if its `image_id`
matches the image installed (`CatalogFreshness`). With none it runs the
extractor (about 6 s), writing atomically. The MCP server does this in the
background from startup and again on a lookup, at most once a minute
(`ProvisionedCatalog`), so a `docker pull` mid-session gets its own catalog;
the agent's CLI in the foreground for the modes that read it (not
`--check-only`); `sheriff:fix` before it repairs. The agent finds the cache
only through its own environment (`XDG_CACHE_HOME`, `HOME`, `USERPROFILE`),
so a test built from a bare map never writes to the real one.

The rest of what makes the jar portable is `ProjectLayout`: the mount and the
component from the working directory (the `src/` rule; a directory inside a
module's `src/` is that module when it has a build file, as Codex runs the
server in the session's directory), the profile the project declares or else
the one its sources call for (`.vue` decides Vue; Sheriff's Vue profile also
reads `.ts`/`.js`, so those cannot; `.py` calls for `PYTHON`), and the test
command from the build tool. `scripts/e2e.sh` scenarios 8, 9, 14 and 18
exercise it.

**Every setting has a default and a name, and is changed in a file**
(`Settings`, its catalog in `src/main/resources/settings.tsv`, a line per
setting): an environment variable, then the project's `.sheriff.properties`,
then `${XDG_CONFIG_HOME:-~/.config}/sheriff-mcp/sheriff.properties` (under
`APPDATA` on Windows), then the default. `McpConfig` and `Configuration` layer
their environment through it, so every setting read as a variable is a
setting. The user's file is found only through the environment given, as the
cache is, so a test built from a bare map reads no one's file; the server
writes it at its defaults, every line commented out, when it starts and finds
none, and when it finds one adds at its end, the same way, the settings it
does not name, so one a newer version brings is listed too; nothing in it is
touched. Settings a cloned project must not choose for whoever opens it
(`AI_BACKEND`) are read from the user's file only. Variables were the only way
before, and Codex passes a server and its hooks almost none. `SHERIFF_IMAGE`
is no setting for now (Kaizten's call): a variable alone, for development and
CI, so a file that sets it is told it does nothing; the catalog's
`project:<image>` scope, which let a project's file set only Kaizten's image,
stays in `Setting` for when it comes back. `sheriff_config`
(and `--config`, `--config NAME=VALUE [--project]`) lists them with their
source and changes one, checking the value first. `--config-ui`, and
`sheriff_config` with `page`, serve the same as a page (`SettingsPage`, the
catalog's `## Title` lines its sections and its fifth field each setting's
name for people, in English and in Spanish from `settings-page-es.tsv`, which
`SettingsTranslationTest` holds to the catalog's settings and sections) through
`Settings.change`, on
`127.0.0.1` under a random token, refusing a foreign `Host` or `Origin` and a
change that is not JSON, as the files name where code is sent and which image
runs; it is started from a daemon thread, as the JDK's `HttpServer` dispatcher
otherwise keeps the JVM alive after the session ends (measured), and closes
with its Close button, after `SettingsPage.IDLE_LIMIT` unused, or with
`Tools.close`. `sheriff_config`'s listing and its page answer name the command
that opens the page (`SettingsPage.openCommand`: the Java and the jar running),
so a user who asks the assistant how to configure Sheriff is told what to type.
The API keys are never settings: a variable alone. A value in a file that its setting does not take, a name that is
no setting, or a user's setting in a project's file is ignored and named
(`Settings.problems`), by the listing and once a session by the tools'
answers. The Maven plugin reads the `.sheriff.properties` at the top of its
build (`SheriffMojo.settingsProject`) and never the user's file, so CI and
the tools agree on a pinned image; a project's own threshold keys (`coverage=` and the rest) are
checked as their settings are, and one they do not take is passed over too. A bad variable is named too but left to what reads it, as some
refuse to start on one. `sheriff_config` is not among the tools the
installers allow without asking, as it writes the user's files.

**A project declares its profile once, and the plugin and the MCP must agree
on where** (`DeclaredProfile`): the `sheriff.profile` property of a
`pom.xml`, read nearest first and only while each directory is a Maven
module, as Maven inherits it; then `profile` in `.sheriff.properties`. Every
`pom.xml` comes before any file, because the Maven build never sees the file.
A declaration applies only to components in its language, and a
single-module project ignores its parent, which is its siblings' too. **No
rule can be taken out**: an exclusion added on a model's claim of a conflict
was measured and withdrawn. `KnownFalsePositives`, inside the one analyzer,
drops only findings measured wrong, each on what the source shows, never a
rule: `FilenamePascalCase` on `package-info.java` and `module-info.java`,
names Java requires; and, since the image of 29 October,
`FourSpaceIndentation` on a comment's `*` lines and inside a text block,
`JavaVariableName` on a `static final` constant and `UnusedImport` on an
import used as an annotation. On this repository's code, clean under the
image of 28 October, those three were 13,981 of 14,879 errors under the new
one, and every `UnusedImport` of the plugin. A line it cannot read keeps
its finding.

## The MCP's answers

**The answers decide the next step, not the model.** Every answer of
`sheriff_test` and `sheriff_fix` ends with one `Next step:` (`NextStep`),
chosen from the state alone, with no alternative in it: when an answer left
a choice, the work differed by model for the same answers. Do not add wording
that offers the model a choice; `ToolsTest` checks the step has none and is
the answer's last line (`NextStep.last`). The work is one loop with one tool:
`sheriff_fix`, what it leaves by hand, `sheriff_fix` again. The test command
a step names is `ProjectLayout.testCommandForModel`: absolute, and without
`-q`. What is left by hand is listed bottom-up per file and followed by the
code Sheriff accepts for it (`AcceptedCode`, from
`src/main/resources/accepted-code.md`): change a shape there only after
checking it against the image.

**The hooks reuse the MCP's verdicts.** `SheriffRunner` records what a full
`sheriff_test` or a repair found in `VerdictCache`, per component directory,
profile and fail-fast, and the gate and the Stop hook read it before running
Sheriff, so a turn that ended on a clean `sheriff_test` is not analyzed again.
An entry is recorded only when every source the fingerprint covers is one
Sheriff analyzed with that content (the SHA-256 in
`sheriff_tracked_files.json`), and served only while the fingerprint matches
and it is younger than `SHERIFF_GATE_TTL`. The gate looks again once it holds
the component's lock, so parallel edits run Sheriff once. `scripts/e2e.sh`
scenario 21 runs it.

**The hooks speak the same language.** A hook ends with the step an analysis
ends with, from `ComponentGate.repairStep`: the `sheriff_fix` tool with the
component named, what done looks like ("Next step: none, the component is
done"), and the command for a session with no sheriff tools, `--call`, which
goes through the same `Tools` and so answers the same (`ToolCall`). Every
step names the component. `scripts/e2e.sh` runs the command a Stop hook
printed, as it stands.

**The answers are as short as the work allows**, judged from the task
records, which hold every answer of every session. `sheriff_test` answers
with counts (`FindingsRenderer.summary`), as its step is the repair. The
repair's list writes the shared folder once, one line per error and a rule's
`howToSolve` once, only for rules `accepted-code.md` does not show. A line is
`SheriffFinding.brief`, as the hooks' sample is: Sheriff pastes Maven's whole
output into a description, and one finding took twenty lines. It holds
whole files, in path order, until they reach
`SheriffFixTool.ERRORS_PER_ANSWER` (20) errors, with the rest counted per
file, and the step names those files and no other (`NextStep.afterRepair`).
`AnswerMemory` says when a list is the same as the last one. Do not point an
answer elsewhere for the rest (a folder, another answer): models went to read
Sheriff's raw JSON instead of fixing. Answers name the task's id only. A
component that is a path inside one is refused before Sheriff runs
(`ToolContext.componentArgument`), and its description gives no example
name, which models copy.

**An error that stays through the work on it is left for the end, not
skipped** (`StuckErrors`, a record per component in the user's temporary
folder, shared by the answers and the hooks). An error is known by its file,
rule and message, not its line. Each answer of `sheriff_fix` that it stays
through after being listed is an attempt; at two it is listed apart, after
the files still being worked on ("Left for the end"); when only such errors
are left they get a last round of their own; at three the step is to tell the
user each one and why it cannot be fixed (`NextStep.afterExhausted`). When the
Stop hook's stops with nothing changed run out, the errors still there are
left for the end at once. A component whose only errors are left for the end
does not block an edit at the gate nor send a turn back: a checker's false
positive (the names Jackson's imported BOM brought into this repository's
`pom.xml`, until the image of 20 October) held up every new file and every
turn. A verdict that stopped at the first error knows only one,
so it never counts as only deferred.

**A repair of Sheriff's that was undone is not made again.** The same record
keeps the errors Sheriff's fixers removed; one of them back later had its
repair undone, which a model does when the repair broke the build, and
every `sheriff_fix` made it again, as an error a repair removes never counts
as an attempt. Such an error is left for the end at once, so it
blocks neither the gate nor the Stop hook, and `SheriffRepair` takes it out
of `sheriff_errors.json` before every `fix`: `fix` repairs only what that
record holds, and with a finding taken out neither the default set nor
`--fixers` naming its rule touched its file (measured on PetClinic, image of
20 October). The answer names it. Tests that fail after a repair that changed
nothing name the files an earlier repair of the session changed
(`AnswerMemory`), and a failed build is shown by `BuildOutput.excerpt`: the
lines that say why, then its end, as Maven puts the cause first and the
answer used to show only the class path that follows it.

**A clean component is not finished while a method the change added has no
test** (`UntestedMethods`): a non-private method with a body in `src/main`
that the last commit does not have must be called from a test file that
changed too. The MCP's clean step names them, after `sheriff_fix` with
`verify` too, as passing tests say nothing of a method none calls, and the Stop hook blocks on
them before the tests. It reads Java declarations by pattern, compares with
`git show HEAD:`, and with no repository checks nothing. `scripts/e2e.sh`
scenario 19 runs it.

**Nor while its tests do not test the change** (`ChangeCoverage`, Java with
Maven only): the lines of `src/main/java` the last commit does not have
(`GitVersionControl.changedLines`) must reach the project's thresholds of
lines run and branches taken (JaCoCo) and PIT mutants killed
(`CoverageThresholds`: 100%, 100% and 80% by default, declared as
`sheriff.coverage`, `sheriff.branches` and `sheriff.mutation` where the
profile is). Branches, as a line run says nothing of the other way its
condition goes. The mutators are `STRONGER,REMOVE_CONDITIONALS,
EXPERIMENTAL_ARGUMENT_PROPAGATION` (`sheriff.mutators`): `ALL` made 497
mutants on PetClinic's `owner` package to their 204, mostly constants no test
kills; PIT 1.30 no longer has the operand mutators (`AOD`, `ROR`, `UOI`). Only the change, as an old project's coverage would fail every
change and PIT on a whole project takes hours. The clean step sends the model
to `sheriff_fix` with `verify` instead of to the tests, as only that measures;
the Stop hook measures too, with the tests on. Both tools run through the
project's Maven with nothing in its `pom.xml`: `pitest-maven` on the command
line runs no JUnit 5 test, so PIT's JUnit 5 plugin goes in as
`additionalClasspathElements`, only when a test imports `org.junit.jupiter`,
since with JUnit 4 alone it stops PIT (measured on Maven 3.10). PIT runs the
tests in its own runner, without surefire's configuration, and refuses a suite
with one that fails there ("Tests failing without mutation"), as this
repository's did over `sheriff.state.home`. So surefire's
`systemPropertyVariables`, from `help:effective-pom` (inherited and
resolved), go to PIT as its `argLine`; PIT splits that at every space, quotes
or not, and its test process dies on a value with one, so such a value is
left out. A measure that cannot run says why and holds nothing. The versions are pinned in
`ChangeCoverage`.

**`SHERIFF_PROTOTYPE=1` takes both away**, for trying an idea out: the clean
step is done, `sheriff_fix` with `verify` does not measure, the Stop hook
checks neither the new methods, the tests nor the coverage, and the repair
loop's test gate does not run (`Composition.testRunner`). Standards stay.

`sheriff_fix`, the loop's first pass and `sheriff:fix` repair with one class,
`SheriffRepair` (`SheriffRunner.fix` and `DeterministicFixer` only call it
and word its outcome): Sheriff's default set, then `--fixers` with every rule
found, again for each profile whose count the round before lowered, until a
round repairs nothing (`SHERIFF_REPAIR_ROUNDS` sets a limit), each round's
analysis measuring the one before. It always ends, as a round runs only when
the count dropped. The limit of three it used to have did not bind on
PetClinic (290 errors to 241 in three rounds, the third repairing nothing,
with and without it), but fixers that make room for each other can need more,
and each round past the limit was work left to a model. The answer says how
many rounds ran when there were several. A `fix` that
did not run is a failure, not a repair that found nothing.
`DeterministicFixerTests` checks the loop and `sheriff_fix` give Sheriff the
same commands.

## Architecture

The agent is hexagonal and **the layering is enforced, not trusted**:
`LayeringTests` fails on any import a layer is not explicitly allowed —
dependencies point inwards, the domain knows nothing outside itself, and only
infrastructure touches I/O or a third-party library. A source outside the
three layers fails it too.

That is what makes the adapters cheap: **the CLI is a driving adapter, the
MCP server is another (`infrastructure/mcp/`), and the Maven Mojo is a third,
in its own module.** The plugin adds the Maven-facing edge and the decision a
build takes from a result; the analysis, the Docker call and the value
objects are the agent's, unchanged.

`ProcessRunner` is the seam everything external goes through, so the exact
`docker` and `claude` invocations are asserted against recorded values rather
than inspected on a mock.

**Sheriff's image is managed, not assumed** (`ImageProvisioning`). Missing,
it is pulled with its own time limit (60 min, not the analysis's 300 s): in
the background from the MCP server's start, with the tools answering "being
downloaded" meanwhile; in the foreground from `--check`, the agent's CLI and
the Maven goals. The hooks never pull: with no image they let the edit
through. Out of date it is replaced at the same points, as the rules to hold
a project to are the newest: Sheriff is published only as `latest`, with no
versions, so the only thing that tells a newer build is the digest Docker Hub publishes for the
tag against the one the local image was pulled by (`RepoDigests`), never the
tag. During that pull the image here is used and the answers say a newer
one is coming; a pull that fails leaves it in use and says why.
`SHERIFF_PULL=check` only reports it, `never` does not ask Docker Hub.

**These tools look for their own updates too** (`ServerUpdates`,
`SHERIFF_UPDATES`, from the user's file only): at most once a day
(`latest-release.properties` in the user's cache), in the background from the
server's start, through the public `releases/latest` redirect
(`GitHubReleases`: no API, so no rate limit and no token, and never a
prerelease). `notify`, the default, has the first answer of a session say so
with the install line and `git pull && scripts/install.sh`; `auto` downloads
the release's jar, checks it against its `.sha256`, and moves it over the
installed one in one move, for the sessions that start after; `never` does not
look. Only a jar outside a build's `target/` is replaced. On Linux a running
server keeps answering after its jar is replaced that way, new classes
included, and a new process starts the new jar (measured); on Windows, where a
running server holds its jar, the move is refused and the answer says the
update failed and how to run it. A jar alone does not redo what the installer
writes for the assistants, so even an update made here names the install line.
The one line no test runs is where `McpServer.main` starts it.

The MCP server writes the protocol to the stream it claims at startup and
points `System.out` at stderr: anything in the core that prints must never
reach the JSON-RPC channel. `--agent` is dispatched before that, so the
agent's CLI keeps its stdout. A `tools/call` runs on a thread of its own, one
at a time in order, so a `ping` or a `tools/list` is answered while Sheriff
or the tests run; `sheriff_guidelines` and `sheriff_task`, which run neither
(`Tools.answersAtOnce`), have a second thread, so they never wait behind a
long analysis. Responses are written whole, and when the input ends the calls
already read are answered first. Background tasks run on one daemon thread;
when the client disconnects the server waits for them. A client that stops
the server instead (Codex sends SIGTERM to the process group, then SIGKILL
about 0.3 s later) triggers a shutdown hook that records every unfinished
task as `interrupted`, which nothing may overwrite afterwards; a background
task's failure waits 0.2 s before it is recorded, as its `git` can die first;
a queued task never starts. Task records are written to a file of their own
and moved into place.

Every Sheriff run holds `MountLock` for its component, across threads and
processes, with the whole `test`, `fix`, `test` sequence held at once, since
Sheriff keeps its state in the component's state folder; two components, or
two single-module projects in one folder, no longer wait for each other. The
lock files, the record of verdicts (`VerdictCache`), and the
markers the hooks leave for their next run, live in a folder of the temporary
directory per user (`TemporaryFolders`; under `sheriff.temp.home` in the
tests, which left a hundred lock files there each run). Markers older than a week are pruned
when their folder is written; lock files never are.

## Windows

**It is written to run on Linux, macOS and Windows**, but CI no longer builds
on Windows (see CI and releases), and when it did it had no Docker and no
assistant, so nothing below ever showed in it. What
differs lives in `Platform`: `cmd.exe /c` instead of `/bin/sh -c` for the
project's tests, paths compared with Sheriff's and git's always written with
`/` (never compare `Path.toString()` with a path Sheriff or git wrote),
Claude Code rules in its POSIX form (`C:\x` as `//c/x`), and executables such
as npm's `claude.cmd` found on the `PATH`. The end-to-end job stays on Linux.

- **WSL's Docker.** With `SHERIFF_DOCKER=wsl` (or `wsl:<distro>`; set for the
  user by `install.sh --docker-wsl` under Git Bash and `install.ps1
  -DockerWsl`) every `docker` command becomes `wsl.exe -e docker`, a bind
  mount's `source=C:\x` written `/mnt/c/x`. The `-e` runs it with no shell,
  which would expand a `$` in a path. A `docker` of the user's own on the
  PATH that forwards to WSL hides every problem here, so measure with it off
  the PATH.
- **A program gets one command line, not a list**, and Java leaves a `"`
  inside an argument for the program to end the argument at. So
  `SystemProcessRunner` quotes every argument holding one by the rules C
  programs read (`Platform.quotedForCommandLine`), except for `cmd.exe` and
  `.cmd`/`.bat`, which read their own way. Without it the extractor's
  `sh -c` scripts reached the container cut, so no catalog was ever
  extracted on Windows, and a mount with a comma lost its quotes.
- **Every shell is a different one.** The server and the hooks run commands
  through `cmd.exe`, Claude Code runs its Bash tool in Git Bash, Codex runs
  PowerShell (5.1 for hooks). A command a model is told to run
  (`testCommandForModel`, `--call`) must work in all of Git Bash, Windows
  PowerShell 5.1 and pwsh (`TestShell.ANY_WINDOWS_SHELL`): `cd 'C:/x';
  ./mvnw.cmd test` for a wrapper, `mvn -f 'C:/x' test`, `gradle -p` or
  `npm --prefix` for a tool on the PATH. Single quotes
  (`Platform.quotedForAnyWindowsShell`), as double ones expand `$` and
  backticks; a folder named with `[ ]` still defeats PowerShell's `cd`. What
  the server and the hooks run is cmd's form, with the wrapper as
  `.\mvnw.cmd` (`TestShell.CMD`), because Claude Code sets
  `NoDefaultCurrentDirectoryInExePath=1`.
- **The loop's rule for Claude Code is `Bash(...)` alone**, naming
  `./mvnw.cmd` (`ClaudeCliFixer.inGitBash`): with a `PowerShell(...)` rule
  beside it the model chose PowerShell, where Claude Code refuses a `cd`
  followed by another command.
- **Java writes a pipe in Cp1252.** `claimStandardOutput` makes both streams
  UTF-8 when they go to a program, and keeps Java's own choice on a terminal,
  where UTF-8 shows mojibake in the console's code page.
- **Codex passes a server and a hook almost none of the user's variables**
  (on Linux `HOME LANG LOGNAME PATH PWD SHELL SHLVL TERM USER`, on Windows a
  score of system ones). So `SHERIFF_DOCKER` goes into the entry's `env` and,
  for its hooks, onto the line as the Java property `sheriff.docker`, which
  `Platform.environment` reads when the variable is not set. The installers
  read it from the environment they are given, never from the process, or
  tests pass in CI and fail on a machine that has it set.
- **A hook's payload may start with a byte order mark** (.NET Framework
  writes one), and is read without it, or the JSON fails and every hook lets
  the turn through silently.

## Rules this repository holds itself to

Kaizten's rules are not a convention to interpret — they are compiled into
the image as profiles. When writing code here:

- **Both modules must stay green under `JAVA`.** Check before finishing, and
  prefer the plugin's own goal for the plugin. Since the image of 19
  October, `JAVA` checks `pom.xml` too, and two findings stay on the
  plugin, each a false positive of the checker: `maven-core` and
  `maven-plugin-api` "outdated", whose version is the Maven the plugin requires, as
  maven-plugin-plugin writes it into the descriptor (3.10.0 refused Maven
  3.9). Its outdated-dependency rule asks the network, so a new release
  anywhere can turn a module red with no change here. The rule has no fixer
  since the image of 26 October, so `sheriff_fix` leaves the plugin's
  `pom.xml` alone (measured). An image whose fixer list (`fix -l`) gives
  `JavaPomOutdatedDependency` one again would write `maven.version` 3.10.0:
  that would be the one change of `sheriff_fix`'s to undo here, or the
  plugin refuses Maven 3.9.
- **JavaDoc is at least three lines** (`/**`, text, `*/`, each on its own
  line) on every method, constructor and field, with `@param` and `@return`.
  No hardcoded strings or numbers in a call, braces on every branch, imports
  in one sorted block, no blank lines or comments inside a method body.
- **Value objects in `domain/valueobject/` need an Object Mother**, and the
  rules are opt-in *by folder*: the same class draws nothing under
  `domain/model/` and several findings under `domain/valueobject/`.

`JAVA_HEXAGONAL` and `JAVA_DDD` each report exactly one error on
`sheriff-mcp-java`: `domain/port` is not an allowed domain directory name.
That is deliberate — in hexagonal architecture the ports *are* domain. Do not
"fix" it by renaming.

**"Measured" means observed in the real tool.** A claim about how Claude
Code, Codex, `agy`, Sheriff or a shell behaves is written as fact here, in a
commit or in code only after running that tool and seeing it; reproducing
the behaviour you expect in something else (passing a line to `cmd /c` by
hand to learn what Codex does with it) is a hypothesis, and is written as
one. The Codex hooks on Windows went through three releases on such a
hypothesis, and one of them sent the user's environment to the model.

## When running the loop

Six backends, and the choice only decides *who answers* — the loop, the
batching, the scope check and the commits are identical for all of them:
`claude_cli` (default, uses the subscription, no key), `codex_cli`,
`antigravity_cli`, `anthropic_api`, `openai_api`, and `local`.

**`openai_api` and `local` are one adapter.** Ollama, LM Studio, vLLM and
llama.cpp all speak OpenAI's `/chat/completions`, so the difference is a base
URL (`OPENAI_BASE_URL`) and whether a key is required. It uses the JDK's HTTP
client, not an SDK, and so does `anthropic_api` (`POST /v1/messages`,
retried twice on 408, 409, 429, 5xx or a dropped connection, as the SDK
does): the SDK and its Kotlin and HTTP stacks would be nine tenths of the
jar and of what the Maven plugin makes every build resolve, so
`LayeringTests` does not allow `com.anthropic`. Both API backends offer
`read_file`/`write_file`, and
**how well that goes is a property of the model, not of the loop**: a model
may never call a tool, or read and stop without writing. Either way the loop
parks the file it made no progress on and cuts. **Both tools are kept to the
component** (`FileTools`, given it by `Composition`), reading as much as
writing, with symbolic links followed: for a single-module project the mount
is the folder of every project beside it.

The loop works on its own branch, commits each pass, and **cuts the run
without committing when the model touches a file outside its batch** — a
scope violation, not a failure of the fix; the work is left in the tree for
review. The order inside a run is tested: branch first, then Sheriff's own
fixers (`AutomaticRepair`, committed on their own), then the model passes.
Git runs from inside the component and translates its paths to the mount's,
so a repository that *is* the module gets the same branch, commits and scope
check; no repository, or any git command failing, stops the run loudly. The
assistant the loop launches carries `SHERIFF_AGENT_RUNNING=1`, and the hooks
stand aside for it.

With `claude_cli` the model may edit only inside the component, by Claude
Code's rule `Edit(//<absolute component path>/**)`, not
`--permission-mode acceptEdits`, which would make every sibling of a
single-module repository editable. **The path must be absolute**: Claude Code
resolves a relative one against the session's current directory, so a model
that `cd`s is denied every edit after it (`ClaudeCliFixerTests` pins it). It
may also run the project's test command, as an exact `Bash(<command>)` rule
without the `cd`; without it a repair pass works blind. The prompt goes to
`claude -p` on standard input, as Linux caps one argument at 128 KB.
`codex_cli` is confined by `--cd <component>`, which also lets it start in a
single-module layout, as it refuses to run outside a git repository.

Antigravity is also an MCP client: it reads the server's instructions and
follows the steps, but has no hooks. `--install-antigravity`
(`AntigravityInstaller`) writes the server where `agy mcp add` does,
`~/.gemini/config/mcp_config.json`, and allows the four tools that do not
commit in `~/.gemini/antigravity-cli/settings.json`
(`mcp(sheriff/sheriff_test)`), as its headless mode refuses any tool it
cannot ask about.

`antigravity_cli` runs `agy` inside the component with
`--mode accept-edits` and the prompt on standard input. Headless, it accepts
writes inside the directory it runs in and refuses everything else but
reading, a refusal ending the session with exit 0 and no output. **Measure
it outside `/tmp`**, where `agy` writes anywhere. Its permissions come only
from `~/.gemini/antigravity-cli/settings.json` and its login from the
keyring, so each pass runs with a `HOME` of its own (`AntigravityHome`): a
copy of the user's settings with `command(<test command>)` and
`command(git mv)` added, deleted after. `command(X)` allows `X` with more
arguments and refuses anything chained to it. Maven and Gradle find their
caches through Java's `user.home`, which ignores that `HOME`, and
`MAVEN_USER_HOME` and npm's two variables point the rest back
(`Configuration.buildToolHomes`). On Windows, where `agy` reads
`USERPROFILE`, none of this has been tried, and it runs with no command. A
session cut at a refused tool is reported rather than failed, and
`--print-timeout` is set to the fixer's own limit, as `agy` gives up after
five minutes. `agy` follows `HOME`; the jar does not, so a trial passes it
`-Duser.home` too.

**Every pass is measured** (`PassMeter`, `RunMetrics`, `MetricsFile`): held
until the analysis after it, which the loop makes anyway, counts its rules in
its batch's files (renamed ones followed; Sheriff's fixers on the whole
component, from what `AutomaticRepair.analyzedBefore` found), and written as a
line of `metrics-*.jsonl` beside the prompt logs with its time and its
`TokenUsage`. Claude Code's comes from its JSON, at list price; Codex's from
the events of `codex exec --json`, which `CodexCliFixer` adds, with its cached
input taken out of its input so the two count alike. The API backends report
none yet.

**A whole run is reported too, on request** (`LoopReport`, beside
`MetricsFile` through `BothMetrics`): `SHERIFF_REPORT=1`, a user's setting
only, as a cloned project must not decide what a machine records, or
`--json-report PATH`. `RunMetrics` has default methods for what the passes
alone do not say (each analysis, what a pass is given, the end), so its
lambdas still compile; `PassMeter` forwards them. The report keeps the old
`--json-report` fields at its top, adds the configuration, versions, project
and commit (`Composition.reportContext`), the first and last analyses, the
passes and their totals, and is written whole when the run ends. Counts,
codes and paths only: a finding's description quotes code. When the last
pass was never measured it analyzes once more, so `final` is what the tree
holds. `scripts/e2e.sh` scenario 22 checks `final` against a check after.

**Runs are compared in the jar, not in a script** (`ReportComparison`,
`--compare-reports PATH... [--csv DIR]`): reports grouped by project
(component and commit), backend, model and strategy, the mean of the runs,
with cost per repair as totals divided, by run, and by rule. One
implementation, as `scripts/benchmark.sh` hands its runs to it instead of its
own Python, which had drifted (its false-positive filter knew one of the
four `KnownFalsePositives` drops) and needed a final `--check-only` the report
now makes unnecessary. `--repo PATH[:COMPONENT]` benchmarks a local project,
cloned at its HEAD for every run.

**A pass's errors are grouped by file or by rule** (`BatchGrouping`,
`SHERIFF_BATCH_BY`, `--batch-by`), as which costs less is a question for the
benchmark, not a rule. By file, the default, a pass is every error of up to
`SHERIFF_MAX_FILES_PER_BATCH` files. By rule it is the rule with the most
errors, in up to that many of its files, and what a pass that lowered nothing
parks is the rule in each file, not the file, so the file's other rules are
still offered; the cap is counted per rule's batches of files.
`SHERIFF_MAX_ERRORS_PER_BATCH` (`--max-errors-per-batch`, 0 for none) caps a
pass's errors on top of either: whole files while they fit, a file that alone
has more giving its first ones, so a pass of one huge file is still small. The
cap then counts the passes the errors need too, and with no cap on the files
only those, as the fallback the grouping gives alone grew the cap every pass.

**The iteration cap grows while the passes make progress**, unless one was
given (`--max-iterations`, `max_iterations` in `sheriff_autofix`). The first
analysis sizes it as one pass per batch of files plus two to spare
(`BatchPolicy.estimateMaxIterations`), and every pass that lowers the count
re-sizes it from the files still with errors (`extendedMaxIterations`), up to
three times the first estimate. A pass that lowers nothing never grows it:
its files are parked, and the run stops when nothing is left to try.

Standards clean is not the same as working: Sheriff's fixers and a partial
pass can break the build. So the loop runs the project's tests however it
stops (`RunSummary.testsPassed`), `sheriff_fix` takes `verify`, the Stop
hook requires green tests with `SHERIFF_STOP_RUNS_TESTS=1`, and `sheriff:fix`
analyzes again and fails on what it could not repair.