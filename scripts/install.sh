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
scratch=""

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
checkout that has one, rules_catalog.json. Registers the server with Claude
Code at user scope as ${SHERIFF_MCP_NAME:-sheriff}, replacing an earlier
registration, and wires the hooks into ~/.claude/settings.json. With codex on
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

# An MCP server that does not start shows only "connection closed" in Claude
# Code, with no reason, so the reason has to be given here.
check_java() {
  if ! command -v java >/dev/null 2>&1; then
    echo "install.sh: Java is not installed. These tools need Java 17 or newer:" >&2
    echo "  https://adoptium.net (Temurin 17 or 21), then run this again." >&2
    exit 1
  fi
  local java_version java_major
  java_version="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
  java_major="${java_version%%.*}"
  if [[ "$java_major" == "1" ]]; then
    java_major="$(echo "$java_version" | cut -d. -f2)"
  fi
  if [[ ! "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 17 )); then
    echo "install.sh: Java ${java_version:-of unknown version} is on the PATH, but these tools need 17 or newer." >&2
    echo "  Install Java 17+ (https://adoptium.net) and make it the one 'java -version' reports." >&2
    exit 1
  fi
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
  mvn -B -q -Dmaven.test.redirectTestOutputToFile=true -f "$repo/pom.xml" install
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

main() {
  local home_dir="${SHERIFF_HOME:-$HOME/.local/share/sheriff-agent}"
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
  trap 'rm -rf "$scratch"' EXIT
  local repo
  repo="$(checkout)"
  if [[ -z "$repo" ]]; then
    from_release=1
  fi
  check_java
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
  echo "Installed $(java -jar "$home_dir/sheriff-mcp.jar" --version) into $home_dir"

  docker_setup "$wanted_install" "$pull" "$image"

  if (( register == 0 )); then
    return 0
  fi
  local jar=(java -jar "$home_dir/sheriff-mcp.jar") hook_options=() server_options=() codex_options=()
  if (( fail_fast == 1 )); then
    hook_options+=(--fail-fast)
  fi
  if (( pull_always == 1 )); then
    server_options+=(-e SHERIFF_PULL=always)
    codex_options+=(--pull-always)
  fi
  # The hooks are only a settings file, so they do not wait for the claude
  # command: Claude Code from the desktop app or an IDE reads that file too.
  if (( hooks == 1 )); then
    "${jar[@]}" --install-hooks --user ${hook_options[@]+"${hook_options[@]}"}
  else
    echo "The hooks were left out (--no-hooks). For every project, or in one project's folder without --user:"
    echo "  java -jar $home_dir/sheriff-mcp.jar --install-hooks --user"
  fi
  if command -v claude >/dev/null 2>&1; then
    claude mcp remove --scope user "$name" >/dev/null 2>&1 || true
    claude mcp add --scope user "$name" ${server_options[@]+"${server_options[@]}"} \
      -- java -jar "$home_dir/sheriff-mcp.jar"
    echo "Registered the MCP server as '$name'. Restart open Claude Code sessions to pick it up."
  else
    echo "claude is not on the PATH, so the MCP server was not registered. By hand:" >&2
    echo "  claude mcp add --scope user $name -- java -jar $home_dir/sheriff-mcp.jar" >&2
  fi
  if (( codex == 1 )); then
    if ! command -v codex >/dev/null 2>&1; then
      echo "codex is not on the PATH, so Codex was left alone. Once it is installed:"
      echo "  java -jar $home_dir/sheriff-mcp.jar --install-codex"
    else
      if (( hooks == 0 )); then
        codex_options+=(--no-hooks)
      fi
      "${jar[@]}" --install-codex ${hook_options[@]+"${hook_options[@]}"} ${codex_options[@]+"${codex_options[@]}"}
      echo "Restart open Codex sessions to pick it up; the first one asks you to review the new hooks."
    fi
  fi
  # Antigravity takes the same --pull-always as Codex, and nothing about hooks:
  # it has none of Sheriff's.
  if (( antigravity == 1 )); then
    if ! command -v agy >/dev/null 2>&1; then
      echo "agy is not on the PATH, so Antigravity was left alone. Once it is installed:"
      echo "  java -jar $home_dir/sheriff-mcp.jar --install-antigravity"
    else
      local antigravity_options=()
      if (( pull_always == 1 )); then
        antigravity_options+=(--pull-always)
      fi
      "${jar[@]}" --install-antigravity ${antigravity_options[@]+"${antigravity_options[@]}"}
      echo "Restart open agy sessions to pick it up."
    fi
  fi
}

main "$@"
