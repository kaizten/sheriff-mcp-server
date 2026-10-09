#!/usr/bin/env bash
# Installs the runnable jar where the hooks and an MCP client can reach it,
# pulls Sheriff's image, and sets up Claude Code and, when it is installed,
# Codex. The point is the location: outside target/, so a `mvn clean` never
# breaks a session that is using it, and in one folder with the rule catalog,
# which it finds beside itself.
#
# From a checkout it builds and tests both modules first. Anywhere else it
# downloads a release's jar with gh, logged in to any GitHub account: that is the
# one line a release gives, and running it again updates everything.
#
#   gh release download -R kaizten/sheriff-mcp-server -p install.sh -O - | bash
#
# Options: --help, or usage() below. Everything runs from main(), on the last
# line, so a script read from a pipe is read whole before any of it runs.
set -euo pipefail

readonly releases="kaizten/sheriff-mcp-server"
readonly docker_desktop="https://desktop.docker.com/mac/main"
readonly minimum_java=17
scratch=""
java_bin=""

usage() {
  cat <<'USAGE'
install.sh [options]      from a checkout: build, test and install
                          anywhere else, or with --release: install a release

  --release[=TAG]   install a release's jar instead of building (the latest,
                    or TAG); needs gh, logged in to any GitHub account,
                    and no Maven
  --no-pull         do not pull Sheriff's image (the MCP server pulls it by
                    itself when it is missing)
  --install-docker  install Docker when it is missing, and start it when it
                    is stopped: Docker's own get.docker.com on Linux (pacman
                    on Arch, which Docker does not package for), and Docker
                    Desktop's installer from docker.com on macOS. Docker
                    Desktop asks you to accept its terms the first time it
                    starts; this does not accept them for you
  --pull-always     have the MCP server pull a newer Sheriff image each time
                    it starts (SHERIFF_PULL=always, written into the server's
                    registration); without it a newer image is only reported.
                    Pass it each time: a run without it resets the default
  --no-hooks        set up the MCP server without the hooks
  --no-codex        leave Codex's config alone
  --no-antigravity  leave Antigravity's config alone
  --fail-fast       the hooks ask Sheriff to stop at the first error
  --no-mcp          install only, without touching any assistant's config

Installs into ${SHERIFF_HOME:-~/.local/share/sheriff-agent}: sheriff-mcp.jar
(the MCP server, the hooks, the CI gate and the agent's CLI) and, from a
checkout that has one, rules_catalog.json. Sets up only the assistants this
machine has, and ends saying which. With Claude Code (claude on the PATH, or
~/.claude, which its desktop app and IDE extensions use), wires the hooks into
~/.claude/settings.json and, with claude on the PATH, registers the server at
user scope as ${SHERIFF_MCP_NAME:-sheriff}, replacing an earlier
registration. With codex on
the PATH, sets Codex up the same way (--install-codex); Codex asks once before
it runs a new hook, and that review stays with you. With agy on the PATH, sets
Antigravity up too (--install-antigravity): the server and the tools it may run
without asking, as it has no Sheriff hooks. The image pulled is
${SHERIFF_IMAGE:-kaizten/sheriff:latest}.
USAGE
}

# The checkout this script sits in, or nothing when it was read from a pipe or
# copied out of one.
checkout() {
  local source="${BASH_SOURCE[0]:-}"
  if [[ "$source" != *install.sh || ! -f "$source" ]]; then
    return 0
  fi
  local candidate
  candidate="$(cd "$(dirname "$source")/.." && pwd)"
  if [[ -f "$candidate/pom.xml" && -d "$candidate/sheriff-mcp-java" ]]; then
    echo "$candidate"
  fi
}

# A list of names as a sentence says it: "A", "A and B", "A, B and C". Bash
# 3.2, which macOS runs this with, has no negative indexes, hence the count.
join_names() {
  local result="" count=$# index=0 name
  for name in "$@"; do
    index=$((index + 1))
    if (( index == 1 )); then
      result="$name"
    elif (( index == count )); then
      result="$result and $name"
    else
      result="$result, $name"
    fi
  done
  echo "$result"
}

# The major version of a Java, or nothing when it does not run or says none.
java_major() {
  local version major
  version="$("$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')" || true
  major="${version%%.*}"
  if [[ "$major" == "1" ]]; then
    major="$(echo "$version" | cut -d. -f2)"
  fi
  if [[ "$major" =~ ^[0-9]+$ ]]; then
    echo "$major"
  fi
}

# A path as this shell writes it, and as the programs it starts read it: the
# same on Linux and macOS; under Git Bash on Windows, C:/x for what is written
# into an assistant's configuration and /c/x for this shell's own use.
native_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    echo "$1"
  fi
}
posix_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -u "$1"
  else
    echo "$1"
  fi
}

# Every java worth trying, best first: JAVA_HOME, the PATH, then the folders
# JDK installers use, newest there first. A JDK installed without touching
# the PATH is found all the same.
java_candidates() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    echo "$(posix_path "$JAVA_HOME")/bin/java"
    echo "$(posix_path "$JAVA_HOME")/bin/java.exe"
  fi
  type -ap java 2>/dev/null || true
  local root vendor
  case "$(uname -s)" in
    Darwin)
      /usr/libexec/java_home -v "$minimum_java+" 2>/dev/null | sed 's|$|/bin/java|' || true
      ;;
    Linux)
      ls -d /usr/lib/jvm/*/bin/java 2>/dev/null | sort -r -V || true
      ;;
    MINGW*|MSYS*|CYGWIN*)
      for root in "${PROGRAMFILES:-}" "${LOCALAPPDATA:+$LOCALAPPDATA/Programs}"; do
        if [[ -z "$root" ]]; then
          continue
        fi
        root="$(posix_path "$root")"
        for vendor in "Eclipse Adoptium" Java Microsoft "Amazon Corretto" Zulu BellSoft; do
          ls -d "$root/$vendor"/*/bin/java.exe 2>/dev/null | sort -r -V || true
        done
      done
      ls -d "$HOME/.jdks"/*/bin/java.exe 2>/dev/null | sort -r -V || true
      ;;
  esac
}

# The Java every assistant is set up with, by its absolute path, in java_bin.
# Not "java": an editor started from the desktop can have another PATH, with
# an older Java or none, and Claude Code on Windows runs the hooks in Git
# Bash, so a Java this terminal finds can be missing there; the server then
# only showed "connection closed", with no reason, so it has to be given here.
# The path is the one found, not where its links lead, so that updating that
# Java does not break it.
find_java() {
  local candidate major
  while IFS= read -r candidate; do
    # Git Bash names java.exe without its extension, and Windows needs it.
    if [[ -n "$candidate" && "$candidate" != *.exe && -f "$candidate.exe" ]]; then
      candidate="$candidate.exe"
    fi
    if [[ -n "$candidate" && -f "$candidate" && -x "$candidate" ]]; then
      major="$(java_major "$candidate")"
      if [[ -n "$major" ]] && (( major >= minimum_java )); then
        java_bin="$candidate"
        echo "Using Java $major at $(native_path "$java_bin")"
        return 0
      fi
    fi
  done < <(java_candidates)
  echo "install.sh: no Java $minimum_java or newer was found: not in JAVA_HOME, not on the PATH," >&2
  echo "  and not where JDKs are installed. Install one (https://adoptium.net, Temurin 17 or 21)" >&2
  echo "  and run this again." >&2
  exit 1
}

check_maven() {
  if ! command -v mvn >/dev/null 2>&1; then
    echo "install.sh: Maven (mvn) is not installed, and it is what builds these tools:" >&2
    echo "  https://maven.apache.org/install.html, or install a release instead: --release" >&2
    exit 1
  fi
}

check_gh() {
  if ! command -v gh >/dev/null 2>&1; then
    echo "install.sh: gh is not installed, and it is what downloads a release:" >&2
    echo "  https://cli.github.com, then 'gh auth login', and run this again." >&2
    exit 1
  fi
  if ! gh auth status >/dev/null 2>&1; then
    echo "install.sh: gh is not logged in. Run 'gh auth login' with any GitHub account." >&2
    exit 1
  fi
}

build_and_copy() {
  local repo="$1" home_dir="$2"
  echo "Building and testing both modules..."
  # Maven runs the Java JAVA_HOME names, or else the one on the PATH, which
  # can be none: then it is given the one found, for the build only.
  local java_home="${JAVA_HOME:-}"
  if [[ -z "$java_home" ]] && ! command -v java >/dev/null 2>&1; then
    java_home="$(dirname "$(dirname "$java_bin")")"
  fi
  JAVA_HOME="$java_home" mvn -B -q -Dmaven.test.redirectTestOutputToFile=true -f "$repo/pom.xml" install
  install -m 0644 "$repo/sheriff-mcp-java/target/sheriff-mcp.jar" "$home_dir/sheriff-mcp.jar"
  if [[ -f "$repo/sheriff-mcp-java/rules_catalog.json" ]]; then
    install -m 0644 "$repo/sheriff-mcp-java/rules_catalog.json" "$home_dir/rules_catalog.json"
  else
    echo "No rules_catalog.json to install: the tools extract their own from Sheriff's image on first use."
  fi
}

sha256_check() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c "$@"
  else
    shasum -a 256 -c "$@"
  fi
}

# The jar comes with its checksum, so a download cut short is never installed.
# A release carries no rule catalog: it is Kaizten's content, and the tools
# extract their own from the image installed.
download_release() {
  local tag="$1" home_dir="$2" downloads="$scratch/release"
  mkdir -p "$downloads"
  echo "Downloading sheriff-mcp.jar from ${tag:-the latest release} of $releases..."
  if ! gh release download ${tag:+"$tag"} -R "$releases" -p sheriff-mcp.jar -p sheriff-mcp.jar.sha256 \
      -D "$downloads"; then
    echo "install.sh: the release could not be downloaded; gh says why above." >&2
    exit 1
  fi
  if ! (cd "$downloads" && sha256_check sheriff-mcp.jar.sha256 >/dev/null); then
    echo "install.sh: the jar downloaded does not match its checksum. Run this again." >&2
    exit 1
  fi
  install -m 0644 "$downloads/sheriff-mcp.jar" "$home_dir/sheriff-mcp.jar"
}

as_root() {
  if [[ "$EUID" -eq 0 ]]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo "$@"
  else
    echo "install.sh: '$*' needs root, and sudo is not installed." >&2
    return 1
  fi
}

install_docker() {
  echo "Installing Docker..."
  case "$(uname -s)" in
    Linux)
      if command -v pacman >/dev/null 2>&1; then
        as_root pacman -S --needed --noconfirm docker || return 1
      else
        curl -fsSL https://get.docker.com | as_root sh || return 1
      fi
      local user="${USER:-$(id -un)}"
      if [[ "$EUID" -ne 0 ]]; then
        as_root usermod -aG docker "$user" || return 1
        echo "Added $user to the docker group. Log out and back in for it to apply to your shell."
      fi
      ;;
    Darwin)
      # Docker's command-line install: https://docs.docker.com/desktop/setup/install/mac-install/
      local architecture=amd64 dmg="$scratch/Docker.dmg" volume="$scratch/docker-volume"
      if [[ "$(uname -m)" == "arm64" ]]; then
        architecture=arm64
      fi
      echo "Downloading Docker Desktop for $architecture from docker.com (about 600 MB)..."
      curl -fL --progress-bar -o "$dmg" "$docker_desktop/$architecture/Docker.dmg" || return 1
      mkdir -p "$volume"
      hdiutil attach "$dmg" -nobrowse -quiet -mountpoint "$volume" || return 1
      local installed=0
      as_root "$volume/Docker.app/Contents/MacOS/install" --user="${USER:-$(id -un)}" && installed=1
      hdiutil detach "$volume" -quiet || true
      if (( installed == 0 )); then
        return 1
      fi
      open -a Docker || true
      echo "Docker Desktop is installed and starting: accept its terms in the window it opens, then"
      echo "run this again to pull Sheriff's image (or let the MCP server pull it when it starts)."
      ;;
    *)
      echo "Install Docker Desktop (https://docs.docker.com/desktop/, with Linux containers, its default)," >&2
      echo "then run this again." >&2
      ;;
  esac
}

start_docker() {
  if [[ "$(uname -s)" == "Darwin" ]]; then
    open -a Docker 2>/dev/null && echo "Docker Desktop is starting; run this again once it is up to pull Sheriff's image."
  elif [[ -d /run/systemd/system ]] && command -v systemctl >/dev/null 2>&1; then
    as_root systemctl enable --now docker.service || true
  fi
  return 0
}

# Pulling here means the first check of a project starts at once instead of
# waiting minutes for 4 GB, and pulling again updates the image: Docker
# downloads only what changed.
docker_setup() {
  local wanted_install="$1" pull="$2" image="$3"
  if ! command -v docker >/dev/null 2>&1; then
    if (( wanted_install == 0 )); then
      echo "Docker is not installed. The install goes on, but nothing can be analyzed until it is:" >&2
      echo "  run this again with --install-docker, or see https://docs.docker.com/get-docker/" >&2
      return 0
    fi
    if ! install_docker; then
      echo "Docker could not be installed (see above). The install goes on without it." >&2
      return 0
    fi
    if ! command -v docker >/dev/null 2>&1; then
      return 0
    fi
  fi
  local docker=(docker)
  if ! docker info >/dev/null 2>&1; then
    if (( wanted_install == 1 )); then
      start_docker
    fi
    if docker info >/dev/null 2>&1; then
      :
    elif [[ "$EUID" -ne 0 ]] && command -v sudo >/dev/null 2>&1 && sudo -n docker info >/dev/null 2>&1; then
      docker=(sudo docker)
    else
      echo "Docker is not running. The install goes on, but nothing can be analyzed until it is;" >&2
      echo "  the MCP server pulls Sheriff's image by itself once it is up." >&2
      return 0
    fi
  fi
  if (( pull == 0 )); then
    return 0
  fi
  echo "Pulling $image (about 4 GB the first time, only what changed after that)..."
  if ! "${docker[@]}" pull "$image"; then
    echo "Sheriff's image could not be pulled now; the MCP server pulls it by itself when it starts." >&2
  fi
}

# The server started as an assistant starts it and asked to initialize: its
# answer must name it sheriff. It ends when its input does. In the install
# folder, which holds nothing to analyze. And kept from Docker: at startup it
# makes sure of Sheriff's image in the background, and on the Windows runner
# the docker it started pulled after a --no-pull, held the folder it ran in,
# and on Windows holds the server's output open until it ends. With no PATH
# and a Docker host that does not exist, any docker it still finds, as
# Windows looks in System32 too, fails at once.
check_server() {
  local java="$1" jar="$2" folder="$3" answer nowhere="unix:///nonexistent/sheriff-install-check.sock"
  local initialize='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"install","version":"1"}}}'
  if command -v cygpath >/dev/null 2>&1; then
    nowhere="npipe:////./pipe/sheriff_install_check"
  fi
  answer="$(cd "$folder" && printf '%s\n' "$initialize" \
    | PATH="" DOCKER_HOST="$nowhere" "$java" -jar "$jar" 2>"$scratch/server.err")" || true
  if [[ "$answer" == *'"serverInfo":{"name":"sheriff"'* ]]; then
    echo "The MCP server starts: OK, it answers as sheriff."
    return 0
  fi
  echo "install.sh: the MCP server does not start: FAIL. It gave no answer to initialize naming it sheriff;" >&2
  echo "  what it said:" >&2
  tail -n 5 "$scratch/server.err" >&2
  exit 1
}

main() {
  local home_dir
  home_dir="$(posix_path "${SHERIFF_HOME:-$HOME/.local/share/sheriff-agent}")"
  local name="${SHERIFF_MCP_NAME:-sheriff}"
  local image="${SHERIFF_IMAGE:-kaizten/sheriff:latest}"
  local register=1 hooks=1 codex=1 antigravity=1 fail_fast=0 pull=1 wanted_install=0 pull_always=0
  local from_release=0 tag="" argument
  for argument in "$@"; do
    case "$argument" in
      --release) from_release=1 ;;
      --release=*) from_release=1; tag="${argument#--release=}" ;;
      --no-pull) pull=0 ;;
      --install-docker) wanted_install=1 ;;
      --pull-always) pull_always=1 ;;
      --no-mcp) register=0 ;;
      --no-hooks) hooks=0 ;;
      --no-codex) codex=0 ;;
      --no-antigravity) antigravity=0 ;;
      --fail-fast) fail_fast=1 ;;
      -h|--help) usage; return 0 ;;
      *) echo "install.sh: unknown option '$argument' (try --help)" >&2; exit 2 ;;
    esac
  done

  scratch="$(mktemp -d)"
  # A scratch folder that cannot be removed is no reason to fail an install
  # that worked: the exit status would be the trap's.
  trap 'rm -rf "$scratch" 2>/dev/null || true' EXIT
  local repo
  repo="$(checkout)"
  if [[ -z "$repo" ]]; then
    from_release=1
  fi
  find_java
  if (( from_release == 1 )); then
    check_gh
  else
    check_maven
  fi

  mkdir -p "$home_dir"
  if (( from_release == 1 )); then
    download_release "$tag" "$home_dir"
  else
    build_and_copy "$repo" "$home_dir"
  fi
  # The agent used to be a jar of its own; a copy left from before would be
  # stale code that nothing updates any more.
  rm -f "$home_dir/sheriff-agent.jar"
  # Under Git Bash, the jar and the Java as Windows names them, for Java and
  # for every assistant's configuration; elsewhere the same paths.
  local java_native jar_native
  java_native="$(native_path "$java_bin")"
  jar_native="$(native_path "$home_dir/sheriff-mcp.jar")"
  echo "Installed $("$java_bin" -jar "$jar_native" --version) into $(native_path "$home_dir")"

  docker_setup "$wanted_install" "$pull" "$image"

  if (( register == 0 )); then
    return 0
  fi
  # The Java found above, by its absolute path, for everything an assistant
  # starts: find_java says why.
  local jar=("$java_bin" -jar "$jar_native") hook_options=() server_options=() codex_options=()
  if (( fail_fast == 1 )); then
    hook_options+=(--fail-fast)
  fi
  if (( pull_always == 1 )); then
    server_options+=(-e SHERIFF_PULL=always)
    codex_options+=(--pull-always)
  fi
  # Only the assistants this machine has are set up, and the run ends saying
  # which: found is every one there, set_up the ones this run wrote into.
  local found=() set_up=()
  # Claude Code is there when its command is, or its folder: the desktop app
  # and the IDE extensions read ~/.claude/settings.json without putting claude
  # on the PATH, so the hooks go in for them too. With neither, nothing is
  # written: a settings file used to be created for an assistant nobody had.
  local claude_cli=0 claude_found=0
  if command -v claude >/dev/null 2>&1; then
    claude_cli=1
  fi
  if (( claude_cli == 1 )) || [[ -d "${HOME:-}/.claude" ]]; then
    claude_found=1
    found+=("Claude Code")
  fi
  if (( claude_found == 0 )); then
    echo "claude is not on the PATH and there is no ~/.claude, so Claude Code was left alone. Once it is installed:"
    echo "  claude mcp add --scope user $name -- \"$java_native\" -jar \"$jar_native\""
    echo "  \"$java_native\" -jar \"$jar_native\" --install-hooks --user"
  else
    local claude_set_up=0
    if (( hooks == 1 )); then
      SHERIFF_JAVA="$java_native" "${jar[@]}" --install-hooks --user ${hook_options[@]+"${hook_options[@]}"}
      claude_set_up=1
    else
      echo "The hooks were left out (--no-hooks). For every project, or in one project's folder without --user:"
      echo "  \"$java_native\" -jar \"$jar_native\" --install-hooks --user"
    fi
    if (( claude_cli == 1 )); then
      # Removed first, so that running this again replaces it.
      claude mcp remove --scope user "$name" >/dev/null 2>&1 || true
      claude mcp add --scope user "$name" ${server_options[@]+"${server_options[@]}"} \
        -- "$java_native" -jar "$jar_native"
      echo "Registered the MCP server as '$name'. Restart open Claude Code sessions to pick it up."
      claude_set_up=1
    else
      echo "claude is not on the PATH, so the MCP server was not registered. By hand:" >&2
      echo "  claude mcp add --scope user $name -- \"$java_native\" -jar \"$jar_native\"" >&2
    fi
    if (( claude_set_up == 1 )); then
      set_up+=("Claude Code")
    fi
  fi
  if command -v codex >/dev/null 2>&1; then
    found+=("Codex")
  fi
  if command -v agy >/dev/null 2>&1; then
    found+=("Antigravity")
  fi
  if (( codex == 1 )); then
    if ! command -v codex >/dev/null 2>&1; then
      echo "codex is not on the PATH, so Codex was left alone. Once it is installed:"
      echo "  \"$java_native\" -jar \"$jar_native\" --install-codex"
    else
      if (( hooks == 0 )); then
        codex_options+=(--no-hooks)
      fi
      SHERIFF_JAVA="$java_native" "${jar[@]}" --install-codex ${hook_options[@]+"${hook_options[@]}"} \
        ${codex_options[@]+"${codex_options[@]}"}
      echo "Restart open Codex sessions to pick it up; the first one asks you to review the new hooks."
      set_up+=("Codex")
    fi
  fi
  # Antigravity takes the same --pull-always as Codex, and nothing about hooks:
  # it has none of Sheriff's.
  if (( antigravity == 1 )); then
    if ! command -v agy >/dev/null 2>&1; then
      echo "agy is not on the PATH, so Antigravity was left alone. Once it is installed:"
      echo "  \"$java_native\" -jar \"$jar_native\" --install-antigravity"
    else
      local antigravity_options=()
      if (( pull_always == 1 )); then
        antigravity_options+=(--pull-always)
      fi
      SHERIFF_JAVA="$java_native" "${jar[@]}" --install-antigravity ${antigravity_options[@]+"${antigravity_options[@]}"}
      echo "Restart open agy sessions to pick it up."
      set_up+=("Antigravity")
    fi
  fi
  # What every assistant set up will run, run once here: a server that does
  # not start shows only "connection closed" once a session opens.
  if (( ${#set_up[@]} > 0 )); then
    check_server "$java_bin" "$jar_native" "$home_dir"
  fi
  # Each assistant left out says so above, among everything else; this is the
  # line that says what the install amounts to.
  echo
  if (( ${#set_up[@]} > 0 )); then
    echo "Sheriff is set up for $(join_names "${set_up[@]}")."
  elif (( ${#found[@]} == 0 )); then
    echo "No assistant was found (Claude Code, Codex or Antigravity), so Sheriff is installed only as"
    echo "the CI check and the Maven plugin:"
    echo "  \"$java_native\" -jar \"$jar_native\" --check"
    echo "Install one of them and run this again to set it up."
  else
    echo "Sheriff is set up for no assistant: the options left out $(join_names "${found[@]}")."
  fi
}

main "$@"
