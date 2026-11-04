#!/usr/bin/env bash
# Takes Sheriff out of every assistant install.sh set up, leaving each file it
# wrote into as it was before the install, and then removes what it installed.
#
# Every assistant's file is rewritten by the installed jar's own uninstallers
# (--uninstall-hooks --user, --uninstall-codex, --uninstall-antigravity): they
# take out Sheriff's lines and, when nothing else in the file changed since the
# install, write back the file as it was, byte for byte, or remove it when the
# install created it. The jar kept those files, as they were, in originals/
# beside itself. Claude Code's server registration goes with its own
# `claude mcp remove`.
#
#   scripts/uninstall.sh              take Sheriff out and remove it
#   scripts/uninstall.sh --disable    take it out of the assistants only
#
# Options: --help, or usage() below. Everything runs from main(), on the last
# line, so a script read from a pipe is read whole before any of it runs.
set -euo pipefail

java_bin=""
found_command=""

usage() {
  cat <<'USAGE'
uninstall.sh [options]    take Sheriff out of every assistant and remove it

  --disable         only take it out of the assistants: the MCP server, the
                    hooks, the tools allowed without asking and Codex's order
                    of work. The jar, its catalog, cache and records stay, and
                    install.sh sets it up again
  --purge           also remove your settings file (sheriff.properties under
                    ${XDG_CONFIG_HOME:-~/.config}/sheriff-mcp)
  --remove-image    also remove Sheriff's Docker image
  --no-claude       leave Claude Code's config alone
  --no-codex        leave Codex's config alone
  --no-antigravity  leave Antigravity's config alone

Each assistant's file is left as it was before the install when nothing else
in it changed since, and removed when the install created it; otherwise only
Sheriff's lines are taken out. Hooks installed into one project's
.claude/settings.json (--install-hooks without --user) are that project's: run
'java -jar <jar> --uninstall-hooks' in it. Removes
${SHERIFF_HOME:-~/.local/share/sheriff-agent}, the cache, the task records and
the temporary folders the hooks use.
USAGE
}

# A path as the Java on this machine reads it: under Git Bash, C:/x, with Git's
# own cygpath, as install.sh does.
native_path() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      if [[ -x /usr/bin/cygpath ]]; then
        /usr/bin/cygpath -m "$1"
        return
      fi
      ;;
  esac
  printf '%s\n' "$1"
}

# The Java the uninstallers run with: the one the installers were given, then
# JAVA_HOME's, then the PATH's.
find_java() {
  local candidate
  for candidate in "${SHERIFF_JAVA:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      java_bin="$candidate"
      return
    fi
  done
}

# A command on the PATH or where its installers put it, as install.sh looks.
find_command() {
  found_command=""
  local folder
  if command -v "$1" >/dev/null 2>&1; then
    found_command="$(command -v "$1")"
    return
  fi
  for folder in "$HOME/.local/bin" "$HOME/.claude/local" "$HOME/.npm-global/bin" "$HOME/.bun/bin" \
      /opt/homebrew/bin /usr/local/bin "${APPDATA:-/nonexistent}/npm"; do
    if [[ -x "$folder/$1" ]]; then
      found_command="$folder/$1"
      return
    fi
  done
}

# Removes a folder, saying so, and why when it cannot.
remove_folder() {
  if [[ ! -e "$1" ]]; then
    return
  fi
  if rm -rf "$1" 2>/dev/null; then
    echo "Removed $(native_path "$1")"
  else
    echo "Could not remove $(native_path "$1") whole: a running session may hold a file in it. Close every" >&2
    echo "  Claude Code, Codex and agy session and run this again." >&2
    failures=$((failures + 1))
  fi
}

main() {
  local home_dir
  home_dir="${SHERIFF_HOME:-$HOME/.local/share/sheriff-agent}"
  local name="${SHERIFF_MCP_NAME:-sheriff}"
  local image="${SHERIFF_IMAGE:-kaizten/sheriff:latest}"
  local disable=0 purge=0 remove_image=0 claude=1 codex=1 antigravity=1 argument
  failures=0
  for argument in "$@"; do
    case "$argument" in
      --disable) disable=1 ;;
      --purge) purge=1 ;;
      --remove-image) remove_image=1 ;;
      --no-claude) claude=0 ;;
      --no-codex) codex=0 ;;
      --no-antigravity) antigravity=0 ;;
      -h|--help) usage; return 0 ;;
      *) echo "uninstall.sh: unknown option '$argument' (try --help)" >&2; exit 2 ;;
    esac
  done
  if (( disable == 1 && (purge == 1 || remove_image == 1) )); then
    echo "uninstall.sh: --disable keeps what is installed; --purge and --remove-image remove it" >&2
    exit 2
  fi

  # The installed jar, as it kept the files it changed beside itself.
  local jar="$home_dir/sheriff-mcp.jar"
  local codex_home="${CODEX_HOME:-$HOME/.codex}"
  local taken=()
  find_java
  if [[ ! -f "$jar" ]]; then
    echo "No jar is installed in $(native_path "$home_dir"), so no assistant's file was touched." >&2
    echo "  If Sheriff was installed elsewhere, run this with SHERIFF_HOME set to that folder." >&2
  elif [[ -z "$java_bin" ]]; then
    echo "uninstall.sh: no Java found (JAVA_HOME, the PATH or SHERIFF_JAVA), and the jar takes Sheriff out" >&2
    echo "  of each assistant's files. Set SHERIFF_JAVA to the Java install.sh used, and run this again." >&2
    exit 1
  else
    local run=("$java_bin" -jar "$(native_path "$jar")")
    if (( claude == 1 )); then
      local claude_bin
      find_command claude
      claude_bin="$found_command"
      if [[ -n "$claude_bin" ]]; then
        if PATH="$(dirname "$claude_bin"):$PATH" "$claude_bin" mcp remove --scope user "$name" >/dev/null 2>&1; then
          echo "Removed the MCP server '$name' from Claude Code."
        else
          echo "Claude Code has no MCP server '$name' at user scope."
        fi
      fi
      if [[ -f "$HOME/.claude/settings.json" ]]; then
        "${run[@]}" --uninstall-hooks --user || failures=$((failures + 1))
      fi
      if [[ -n "$claude_bin" || -f "$HOME/.claude/settings.json" ]]; then
        taken+=("Claude Code")
      fi
    fi
    if (( codex == 1 )) && [[ -d "$codex_home" ]]; then
      "${run[@]}" --uninstall-codex || failures=$((failures + 1))
      taken+=("Codex")
    fi
    if (( antigravity == 1 )) && [[ -d "$HOME/.gemini" ]]; then
      "${run[@]}" --uninstall-antigravity || failures=$((failures + 1))
      taken+=("Antigravity")
    fi
  fi

  if (( disable == 0 )); then
    remove_folder "$home_dir"
    remove_folder "${XDG_CACHE_HOME:-$HOME/.cache}/sheriff-mcp"
    remove_folder "${XDG_STATE_HOME:-$HOME/.local/state}/sheriff-mcp"
    # The hooks' locks, verdicts and markers, a folder per user in Java's
    # temporary directory: /tmp on Linux, TMPDIR on macOS.
    local user temporary purpose
    user="$(id -un 2>/dev/null || echo "${USER:-user}")"
    user="$(printf '%s' "$user" | tr -c 'A-Za-z0-9._-' '_')"
    for temporary in /tmp "${TMPDIR:-/tmp}"; do
      for purpose in gate locks stop stuck turn verdicts; do
        remove_folder "${temporary%/}/sheriff-$purpose-$user"
      done
    done
    case "$(uname -s)" in
      MINGW*|MSYS*|CYGWIN*)
        # install.sh --docker-wsl set it for the user with setx.
        if reg query 'HKCU\Environment' //v SHERIFF_DOCKER >/dev/null 2>&1; then
          reg delete 'HKCU\Environment' //v SHERIFF_DOCKER //f >/dev/null && echo "Removed SHERIFF_DOCKER from your variables."
        fi
        ;;
    esac
  fi
  if (( purge == 1 )); then
    if [[ -n "${XDG_CONFIG_HOME:-}" ]]; then
      remove_folder "$XDG_CONFIG_HOME/sheriff-mcp"
    elif [[ -n "${APPDATA:-}" ]]; then
      remove_folder "$APPDATA/sheriff-mcp"
    else
      remove_folder "$HOME/.config/sheriff-mcp"
    fi
  fi
  if (( remove_image == 1 )); then
    if docker image rm "$image" >/dev/null 2>&1; then
      echo "Removed Sheriff's image $image."
    else
      echo "Sheriff's image $image was not removed: it is not here, or a container still uses it." >&2
    fi
  fi

  echo
  if (( ${#taken[@]} > 0 )); then
    local names="${taken[0]}" index
    for (( index = 1; index < ${#taken[@]}; index++ )); do
      if (( index == ${#taken[@]} - 1 )); then names="$names and ${taken[index]}"; else names="$names, ${taken[index]}"; fi
    done
    echo "Sheriff is no longer set up for $names. Restart open sessions to drop it."
  fi
  if (( disable == 1 )); then
    echo "It is still installed: install.sh sets it up again."
  elif (( purge == 0 )); then
    echo "Your settings file, if you have one, is kept: --purge removes it."
  fi
  if (( failures > 0 )); then
    exit 1
  fi
}

main "$@"
