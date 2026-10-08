# Installs the runnable jar where the hooks and an MCP client can reach it,
# pulls Sheriff's image, and sets up Claude Code and, when it is installed,
# Codex: the Windows counterpart of install.sh.
#
# From a checkout it builds and tests both modules first. Anywhere else it
# downloads a release's jar with gh, logged in to any GitHub account: that is the
# one line a release gives, and running it again updates everything.
#
#   & ([scriptblock]::Create((gh release download -R kaizten/sheriff-mcp-server -p install.ps1 -O - | Out-String)))
#
# Options go after that line, or after scripts\install.ps1:
#   -Release [-Tag TAG]  install a release's jar instead of building (the
#                        latest, or TAG); needs gh, logged in to any GitHub
#                        account, and no Maven
#   -NoPull              do not pull Sheriff's image (the MCP server pulls it
#                        by itself when it is missing)
#   -InstallDocker       install Docker Desktop from docker.com when it is
#                        missing, for this user only (no administrator, WSL 2,
#                        as Docker recommends), and start it when it is
#                        stopped. Docker Desktop asks you to accept its terms
#                        the first time it starts; this does not accept them
#                        for you
#   -PullAlways          have the MCP server pull a newer Sheriff image each
#                        time it starts (SHERIFF_PULL=always, written into the
#                        server's registration); without it a newer image is
#                        only reported. Pass it each time: a run without it
#                        resets the default
#   -NoHooks             set up the MCP server without the hooks
#   -NoCodex             leave Codex's config alone
#   -NoAntigravity       leave Antigravity's config alone
#   -FailFast            the hooks ask Sheriff to stop at the first error
#   -NoMcp               install only, without touching any assistant's config
#
# Installs into $env:SHERIFF_HOME, or %USERPROFILE%\.local\share\sheriff-agent:
#   sheriff-mcp.jar      the MCP server, the hooks, the CI gate and the agent
#   rules_catalog.json   from a checkout that has one
#
# The hooks go into %USERPROFILE%\.claude\settings.json: they guard every
# project Claude Code opens, and let everything through where there is
# nothing Sheriff analyzes. With codex on the PATH, Codex is set up the same
# way (--install-codex). Codex asks once before it runs a new hook; that
# review stays with you. With agy on the PATH, Antigravity is set up too
# (--install-antigravity): the server and the tools it may run without asking,
# as it has no Sheriff hooks.
#
# Two rules for whoever edits this file. It never calls exit: run as a script
# block, exit would close the window it runs in. And it is ASCII only:
# Windows PowerShell decodes gh's output in the console's code page.
#
# If PowerShell refuses to run it as a file, allow local scripts for this session:
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
param(
    [switch]$Release, [string]$Tag, [switch]$NoPull, [switch]$InstallDocker, [switch]$PullAlways,
    [switch]$NoMcp, [switch]$NoHooks, [switch]$NoCodex, [switch]$NoAntigravity, [switch]$FailFast
)
$ErrorActionPreference = 'Stop'

$releases = 'kaizten/sheriff-mcp-server'
$repo = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { $null }
$fromRelease = $Release -or $Tag -or -not $repo -or -not (Test-Path (Join-Path $repo 'pom.xml'))
$homeDir = if ($env:SHERIFF_HOME) { $env:SHERIFF_HOME } else { Join-Path $HOME '.local\share\sheriff-agent' }
$name = if ($env:SHERIFF_MCP_NAME) { $env:SHERIFF_MCP_NAME } else { 'sheriff' }
$image = if ($env:SHERIFF_IMAGE) { $env:SHERIFF_IMAGE } else { 'kaizten/sheriff:latest' }

# A native command run for its exit code alone. Windows PowerShell turns the
# stderr of a native command into errors when it is redirected, and 'Stop'
# made them fatal: a first install aborted on 'claude mcp remove' finding
# nothing to remove. Only for commands known to exist.
function Test-Native([scriptblock]$Command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command *> $null
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $saved
    }
}

# Checked before anything is built: an MCP server that cannot start shows only
# "connection closed" in Claude Code, so the reason has to be given here.
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "Java is not installed. These tools need Java 17 or newer: https://adoptium.net, then run this again."
}
# Through a shell, as java writes its version to stderr. $IsWindows does not
# exist in Windows PowerShell, which only runs on Windows.
$javaVersion = if ($IsLinux -or $IsMacOS) { & sh -c 'java -version 2>&1' } else { & cmd /c 'java -version 2>&1' }
$versionLine = ($javaVersion | Select-String 'version' | Select-Object -First 1).ToString()
if ($versionLine -notmatch '"(\d+)(\.(\d+))?') {
    Write-Error "Could not read the Java version from: $versionLine"
}
$major = [int]$Matches[1]
if ($major -eq 1) { $major = [int]$Matches[3] }
if ($major -lt 17) {
    Write-Error "Java $major is on the PATH, but these tools need 17 or newer. Install Java 17+ (https://adoptium.net) and make it the one 'java -version' reports."
}
if ($fromRelease) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Write-Error "gh is not installed, and it is what downloads a release: https://cli.github.com, then 'gh auth login', and run this again."
    }
    if (-not (Test-Native { gh auth status })) {
        Write-Error "gh is not logged in. Run 'gh auth login' with any GitHub account."
    }
} elseif (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "Maven (mvn) is not installed, and it is what builds these tools: https://maven.apache.org/install.html, or install a release instead: -Release"
}

New-Item -ItemType Directory -Force -Path $homeDir | Out-Null
$jar = Join-Path $homeDir 'sheriff-mcp.jar'
if ($fromRelease) {
    # The jar comes with its checksum, so a download cut short is never
    # installed. A release carries no rule catalog: it is Kaizten's content,
    # and the tools extract their own from the image installed.
    $downloads = Join-Path ([System.IO.Path]::GetTempPath()) ('sheriff-' + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $downloads | Out-Null
    try {
        $which = if ($Tag) { $Tag } else { 'the latest release' }
        Write-Host "Downloading sheriff-mcp.jar from $which of $releases..."
        $download = @('release', 'download')
        if ($Tag) { $download += $Tag }
        & gh @download -R $releases -p sheriff-mcp.jar -p sheriff-mcp.jar.sha256 -D $downloads
        if ($LASTEXITCODE -ne 0) { Write-Error "The release could not be downloaded; gh says why above." }
        $expected = ((Get-Content -Raw (Join-Path $downloads 'sheriff-mcp.jar.sha256')).Trim() -split '\s+')[0]
        $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $downloads 'sheriff-mcp.jar')).Hash
        if ($expected -ne $actual) { Write-Error "The jar downloaded does not match its checksum. Run this again." }
        Copy-Item -Force (Join-Path $downloads 'sheriff-mcp.jar') $jar
    } finally {
        Remove-Item -Recurse -Force $downloads
    }
} else {
    Write-Host "Building and testing both modules..."
    & mvn -B -q '-Dmaven.test.redirectTestOutputToFile=true' -f (Join-Path $repo 'pom.xml') install
    if ($LASTEXITCODE -ne 0) { Write-Error "The build failed; see Maven's output above." }
    Copy-Item -Force (Join-Path $repo 'sheriff-mcp-java\target\sheriff-mcp.jar') $jar
    $catalog = Join-Path $repo 'sheriff-mcp-java\rules_catalog.json'
    if (Test-Path $catalog) {
        Copy-Item -Force $catalog (Join-Path $homeDir 'rules_catalog.json')
    } else {
        Write-Host "No rules_catalog.json to install: the tools extract their own from Sheriff's image on first use."
    }
}
# The agent used to be a jar of its own; a copy left from before would be
# stale code that nothing updates any more.
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $homeDir 'sheriff-agent.jar')
$installed = & java -jar $jar --version
if ($LASTEXITCODE -ne 0) { Write-Error "The jar installed does not start; see the message above." }
Write-Host "Installed $installed into $homeDir"

# Pulling here means the first check of a project starts at once instead of
# waiting minutes for 4 GB, and pulling again updates the image: Docker
# downloads only what changed.
# Docker's command-line install: https://docs.docker.com/desktop/setup/install/windows-install/
# Per user first, as Docker installs it now; for all users, as it used to.
$desktops = @()
if ($env:LOCALAPPDATA) { $desktops += Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe' }
if ($env:ProgramFiles) { $desktops += Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe' }
if ($InstallDocker -and -not (Get-Command docker -ErrorAction SilentlyContinue)) {
    $architecture = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' }
    $installer = Join-Path ([System.IO.Path]::GetTempPath()) 'Docker Desktop Installer.exe'
    Write-Host "Downloading Docker Desktop for $architecture from docker.com (about 600 MB)..."
    $savedProgress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -UseBasicParsing -OutFile $installer "https://desktop.docker.com/win/main/$architecture/Docker%20Desktop%20Installer.exe"
        $setup = Start-Process -FilePath $installer -ArgumentList 'install', '--user', '--quiet' -Wait -PassThru
        if ($setup.ExitCode -eq 0) {
            Write-Host "Docker Desktop is installed. Start it, accept its terms, keep Linux containers, and run this again to pull Sheriff's image. It needs WSL 2: if it says so, run 'wsl --install' and restart."
        } else {
            Write-Warning "Docker Desktop's installer ended with $($setup.ExitCode). The install goes on without it."
        }
    } catch {
        Write-Warning "Docker Desktop could not be downloaded or installed: $($_.Exception.Message). The install goes on without it."
    } finally {
        $ProgressPreference = $savedProgress
        Remove-Item -Force -ErrorAction SilentlyContinue $installer
    }
}
$dockerRunning = $false
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    if (-not $InstallDocker) {
        Write-Warning "Docker is not installed. The install goes on, but nothing can be analyzed until it is: run this again with -InstallDocker, or see https://docs.docker.com/desktop/ (use Linux containers, its default)."
    }
} elseif (Test-Native { docker info }) {
    $dockerRunning = $true
} else {
    $desktop = $desktops | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($InstallDocker -and $desktop) {
        Start-Process $desktop
        Write-Host "Docker Desktop is starting. Run this again once it is up to pull Sheriff's image, or let the MCP server pull it when it starts."
    } else {
        Write-Warning "Docker is not running. The install goes on, but nothing can be analyzed until it is; the MCP server pulls Sheriff's image by itself once it is up."
    }
}
if ($dockerRunning -and -not $NoPull) {
    Write-Host "Pulling $image (about 4 GB the first time, only what changed after that)..."
    & docker pull $image
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Sheriff's image could not be pulled now (with Windows containers, switch Docker Desktop to Linux containers); the MCP server pulls it by itself when it starts."
    }
}

if ($NoMcp) { return }
$hookOptions = @()
if ($FailFast) { $hookOptions += '--fail-fast' }
$serverOptions = @()
$codexOptions = @()
if ($PullAlways) {
    $serverOptions += @('-e', 'SHERIFF_PULL=always')
    $codexOptions += '--pull-always'
}
# The hooks are only a settings file, so they do not wait for the claude
# command: Claude Code from the desktop app or an IDE reads that file too.
if ($NoHooks) {
    Write-Host "The hooks were left out (-NoHooks). For every project, or in one project's folder without --user:"
    Write-Host "  java -jar `"$jar`" --install-hooks --user"
} else {
    & java -jar $jar --install-hooks --user @hookOptions
    if ($LASTEXITCODE -ne 0) { Write-Error "The hooks could not be written; see the message above." }
}
if (Get-Command claude -ErrorAction SilentlyContinue) {
    $null = Test-Native { claude mcp remove --scope user $name }
    # Quoted, or PowerShell takes -- as its own end of parameters and drops it.
    & claude mcp add --scope user $name @serverOptions '--' java -jar $jar
    if ($LASTEXITCODE -ne 0) { Write-Error "The MCP server could not be registered; see the message above." }
    Write-Host "Registered the MCP server as '$name'. Restart open Claude Code sessions to pick it up."
} else {
    Write-Host "claude is not on the PATH, so the MCP server was not registered. By hand:"
    Write-Host "  claude mcp add --scope user $name -- java -jar `"$jar`""
}
if (-not $NoCodex) {
    if (-not (Get-Command codex -ErrorAction SilentlyContinue)) {
        Write-Host "codex is not on the PATH, so Codex was left alone. Once it is installed:"
        Write-Host "  java -jar `"$jar`" --install-codex"
    } else {
        if ($NoHooks) { $codexOptions += '--no-hooks' }
        & java -jar $jar --install-codex @hookOptions @codexOptions
        if ($LASTEXITCODE -ne 0) { Write-Error "Codex could not be set up; see the message above." }
        Write-Host "Restart open Codex sessions to pick it up; the first one asks you to review the new hooks."
    }
}
# Antigravity takes the same -PullAlways as Codex, and nothing about hooks: it
# has none of Sheriff's.
if (-not $NoAntigravity) {
    if (-not (Get-Command agy -ErrorAction SilentlyContinue)) {
        Write-Host "agy is not on the PATH, so Antigravity was left alone. Once it is installed:"
        Write-Host "  java -jar `"$jar`" --install-antigravity"
    } else {
        $antigravityOptions = @()
        if ($PullAlways) { $antigravityOptions += '--pull-always' }
        & java -jar $jar --install-antigravity @antigravityOptions
        if ($LASTEXITCODE -ne 0) { Write-Error "Antigravity could not be set up; see the message above." }
        Write-Host "Restart open agy sessions to pick it up."
    }
}
