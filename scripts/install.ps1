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
# Java 17 or newer is looked for in JAVA_HOME, on the PATH and where JDKs are
# installed, and every assistant is set up to run it by its absolute path.
# With none, Temurin 21 is installed with winget, and so is gh when a release
# is installed without it. A Java update that removes the folder of the old
# one breaks that path: run this again after it.
#
# Only the assistants this machine has are set up, and the run ends saying
# which, after starting the server once as they will. With Claude Code
# (claude on the PATH, or %USERPROFILE%\.claude, which its desktop app and IDE
# extensions use), the hooks go into
# %USERPROFILE%\.claude\settings.json: they guard every project Claude Code
# opens, and let everything through where there is nothing Sheriff analyzes;
# with claude on the PATH the server is registered too. With codex on the
# PATH, Codex is set up the same
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
# nothing to remove. Only for commands known to exist. Its answer is the
# value it returns, so it leaves LASTEXITCODE at 0: a 'docker info' that found
# Docker stopped was the last native command of an install with -NoMcp, and a
# caller that exits with LASTEXITCODE, as GitHub Actions does, failed an
# install that had worked.
function Test-Native([scriptblock]$Command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command *> $null
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $saved
        $global:LASTEXITCODE = 0
    }
}

# A native program run without PowerShell in between, for its exit code and
# both its streams: Windows PowerShell turns a redirected stderr into errors,
# and java writes its version there. Text, when given, is its standard input,
# which is then closed, after an empty line: Windows PowerShell's .NET puts a
# byte order mark before what it writes when the console is UTF-8, and a
# server that read it glued to the first message answered "Invalid JSON".
# Environment adds to or replaces its variables. One that has not ended, or
# whose output has not, after Seconds is killed.
function Invoke-Captured([string]$File, [string]$Arguments, [string]$Directory, [string]$Text, [int]$Seconds = 30, [hashtable]$Environment = @{}) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $File
    $start.Arguments = $Arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    if ($Directory) { $start.WorkingDirectory = $Directory }
    foreach ($variable in $Environment.Keys) { $start.EnvironmentVariables[$variable] = $Environment[$variable] }
    $process = [System.Diagnostics.Process]::Start($start)
    $output = $process.StandardOutput.ReadToEndAsync()
    $errors = $process.StandardError.ReadToEndAsync()
    # A program that ends before reading its input, as java does when the jar
    # cannot be opened, breaks the pipe: its exit code and stderr say why.
    try {
        if ($Text) {
            $process.StandardInput.WriteLine()
            $process.StandardInput.WriteLine($Text)
        }
        $process.StandardInput.Close()
    } catch { }
    if (-not $process.WaitForExit($Seconds * 1000)) {
        try { $process.Kill() } catch { }
        return [pscustomobject]@{ ExitCode = $null; Output = ''; Errors = '' }
    }
    if (-not $output.Wait($Seconds * 1000) -or -not $errors.Wait($Seconds * 1000)) {
        return [pscustomobject]@{ ExitCode = $null; Output = ''; Errors = '' }
    }
    return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $output.Result; Errors = $errors.Result }
}

# The major version of a Java, or 0 when it does not run or says none.
function Get-JavaMajor([string]$Java) {
    try { $run = Invoke-Captured $Java '-version' } catch { return 0 }
    if ($run.ExitCode -ne 0 -or ($run.Errors + $run.Output) -notmatch 'version "(\d+)(\.(\d+))?') { return 0 }
    $major = [int]$Matches[1]
    if ($major -eq 1) { $major = [int]$Matches[3] }
    return $major
}

# The Java every assistant is set up with, by its absolute path, or nothing.
# Not "java": Claude Code runs the hooks in Git Bash, and an editor started
# from the desktop can have another PATH, so a Java the terminal finds can be
# missing there, and the server then only showed "connection closed" and the
# hooks a "non-blocking" error. JAVA_HOME first, then the PATH, then the
# folders JDK installers use, newest there first: a JDK installed without
# touching the PATH, as Temurin's MSI can be, is found all the same.
# $IsWindows does not exist in Windows PowerShell, which only runs on Windows.
function Find-Java {
    $executable = if ($IsLinux -or $IsMacOS) { 'java' } else { 'java.exe' }
    $first = @()
    if ($env:JAVA_HOME) { $first += Join-Path (Join-Path $env:JAVA_HOME 'bin') $executable }
    $first += Get-Command java -CommandType Application -All -ErrorAction SilentlyContinue | ForEach-Object { $_.Source }
    if ($IsMacOS -and (Test-Path '/usr/libexec/java_home')) {
        $run = Invoke-Captured '/usr/libexec/java_home' "-v $minimumJava+"
        if ($run.ExitCode -eq 0) { $first += Join-Path $run.Output.Trim() 'bin/java' }
    }
    foreach ($java in $first) {
        if ((Test-Path -PathType Leaf $java) -and (Get-JavaMajor $java) -ge $minimumJava) { return $java }
    }
    $folders = @()
    if ($IsLinux) {
        $folders += Get-ChildItem -Directory -ErrorAction SilentlyContinue '/usr/lib/jvm'
    } elseif (-not $IsMacOS) {
        $roots = @($env:ProgramFiles, ${env:ProgramFiles(x86)})
        if ($env:LOCALAPPDATA) { $roots += Join-Path $env:LOCALAPPDATA 'Programs' }
        foreach ($root in ($roots | Where-Object { $_ })) {
            foreach ($vendor in 'Eclipse Adoptium', 'Java', 'Microsoft', 'Amazon Corretto', 'Zulu', 'BellSoft') {
                $folders += Get-ChildItem -Directory -ErrorAction SilentlyContinue (Join-Path $root $vendor)
            }
        }
        $folders += Get-ChildItem -Directory -ErrorAction SilentlyContinue (Join-Path $HOME '.jdks')
    }
    $found = foreach ($folder in $folders) {
        $java = Join-Path (Join-Path $folder.FullName 'bin') $executable
        if (Test-Path -PathType Leaf $java) {
            $major = Get-JavaMajor $java
            if ($major -ge $minimumJava) { [pscustomobject]@{ Path = $java; Major = $major; Name = $folder.Name } }
        }
    }
    $newest = $found | Sort-Object -Property @{ Expression = 'Major'; Descending = $true }, @{ Expression = 'Name'; Descending = $true } | Select-Object -First 1
    if ($newest) { return $newest.Path }
    return $null
}

# Installs what is missing with winget, when this is Windows and it has
# winget, and adds to this session's PATH what the install added to the
# user's: a new window would see it, this one does not. Returns whether winget
# ran; whether it worked is for the caller to look for.
function Install-WithWinget([string]$Id, [string]$What) {
    if ($IsLinux -or $IsMacOS -or -not (Get-Command winget -ErrorAction SilentlyContinue)) { return $false }
    Write-Host "$What is not installed; installing it with winget ($Id)..."
    & winget install --id $Id --exact --source winget --silent
    $known = $env:Path -split ';'
    foreach ($scope in 'Machine', 'User') {
        foreach ($entry in ([Environment]::GetEnvironmentVariable('Path', $scope) -split ';')) {
            if ($entry -and $known -notcontains $entry) {
                $env:Path = "$env:Path;$entry"
                $known += $entry
            }
        }
    }
    $global:LASTEXITCODE = 0
    return $true
}

# Checked before anything is built: an MCP server that cannot start shows only
# "connection closed" in Claude Code, so the reason has to be given here.
$minimumJava = 17
$java = Find-Java
if (-not $java -and (Install-WithWinget 'EclipseAdoptium.Temurin.21.JDK' "Java $minimumJava or newer")) {
    $java = Find-Java
}
if (-not $java) {
    Write-Error ("No Java $minimumJava or newer was found: not in JAVA_HOME, not on the PATH, and not where JDKs are installed. " +
        "Install one (https://adoptium.net, or 'winget install EclipseAdoptium.Temurin.21.JDK') and run this again.")
}
Write-Host "Using Java $(Get-JavaMajor $java) at $java"
if ($fromRelease) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue) -and -not (Install-WithWinget 'GitHub.cli' 'gh')) {
        Write-Error "gh is not installed, and it is what downloads a release: https://cli.github.com, then 'gh auth login', and run this again."
    }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Write-Error "gh could not be installed with winget (see above): https://cli.github.com, then 'gh auth login', and run this again."
    }
    if (-not (Test-Native { gh auth status })) {
        Write-Host "gh is not logged in; log in with any GitHub account:"
        & gh auth login
        if (-not (Test-Native { gh auth status })) {
            Write-Error "gh is not logged in. Run 'gh auth login' with any GitHub account, and run this again."
        }
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
    # Maven runs the Java JAVA_HOME names, or else the one on the PATH, which
    # can be none: then it is given the one found above, for the build only.
    $savedJavaHome = $env:JAVA_HOME
    if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
    }
    try {
        & mvn -B -q '-Dmaven.test.redirectTestOutputToFile=true' -f (Join-Path $repo 'pom.xml') install
    } finally {
        $env:JAVA_HOME = $savedJavaHome
    }
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
$installed = & $java -jar $jar --version
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
# How a line printed for the user runs the Java found, in PowerShell.
$javaCommand = "& `"$java`""
# A list of names as a sentence says it: "A", "A and B", "A, B and C".
function Join-Names([string[]]$Names) {
    if ($Names.Count -le 1) { return ($Names -join '') }
    return (($Names[0..($Names.Count - 2)]) -join ', ') + ' and ' + $Names[-1]
}
# The server started as an assistant starts it and asked to initialize: its
# answer must name it sheriff. Returns why it failed, or nothing when it
# answered. In the install folder, which holds nothing to analyze. And kept
# from Docker: at startup it makes sure of Sheriff's image in the background,
# and on the Windows runner the docker it started pulled after a -NoPull, held
# the folder it ran in, and on Windows holds the server's output open until it
# ends. With no PATH and a Docker host that does not exist, any docker it still
# finds, as Windows looks in System32 too, fails at once.
function Test-Server {
    $initialize = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"install","version":"1"}}}'
    $nowhere = if ($IsLinux -or $IsMacOS) { 'unix:///nonexistent/sheriff-install-check.sock' } else { 'npipe:////./pipe/sheriff_install_check' }
    try {
        $run = Invoke-Captured $java "-jar `"$jar`"" $homeDir $initialize 60 @{ PATH = ''; DOCKER_HOST = $nowhere }
    } catch {
        return "$java could not be started: $($_.Exception.Message)"
    }
    if ($null -eq $run.ExitCode) { return "it did not answer within 60 s" }
    if ($run.Output -match '"serverInfo":\{"name":"sheriff"') { return $null }
    $said = (($run.Output.Trim() + "`n" + $run.Errors.Trim()).Trim() -split "`n" | Select-Object -Last 4) -join ' '
    return "it gave no answer to initialize naming it sheriff (exit code $($run.ExitCode)). $said".Trim()
}
# Only the assistants this machine has are set up, and the run ends saying
# which: found is every one there, setUp the ones this run wrote into.
$found = @()
$setUp = @()
# The jar's installers write the Java found into the hooks and the servers
# they set up, rather than "java" (Find-Java says why). For this run only:
# the script runs in the user's own session.
$savedSheriffJava = $env:SHERIFF_JAVA
$env:SHERIFF_JAVA = $java
try {
    # Claude Code is there when its command is, or its folder: the desktop app
    # and the IDE extensions read .claude\settings.json without putting claude on
    # the PATH, so the hooks go in for them too. With neither, nothing is written:
    # a settings file used to be created for an assistant nobody had.
    $claudeCli = [bool](Get-Command claude -ErrorAction SilentlyContinue)
    $claudeFound = $claudeCli -or (Test-Path (Join-Path $HOME '.claude'))
    if (-not $claudeFound) {
        Write-Host "claude is not on the PATH and there is no .claude folder, so Claude Code was left alone. Once it is installed:"
        Write-Host "  claude mcp add --scope user $name -- `"$java`" -jar `"$jar`""
        Write-Host "  $javaCommand -jar `"$jar`" --install-hooks --user"
    } else {
        $found += 'Claude Code'
        $claudeSetUp = $false
        if ($NoHooks) {
            Write-Host "The hooks were left out (-NoHooks). For every project, or in one project's folder without --user:"
            Write-Host "  $javaCommand -jar `"$jar`" --install-hooks --user"
        } else {
            & $java -jar $jar --install-hooks --user @hookOptions
            if ($LASTEXITCODE -ne 0) { Write-Error "The hooks could not be written; see the message above." }
            $claudeSetUp = $true
        }
        if ($claudeCli) {
            # Removed first, so that running this again replaces it.
            $null = Test-Native { claude mcp remove --scope user $name }
            # Quoted, or PowerShell takes -- as its own end of parameters and drops it.
            & claude mcp add --scope user $name @serverOptions '--' $java -jar $jar
            if ($LASTEXITCODE -ne 0) { Write-Error "The MCP server could not be registered; see the message above." }
            Write-Host "Registered the MCP server as '$name'. Restart open Claude Code sessions to pick it up."
            $claudeSetUp = $true
        } else {
            Write-Host "claude is not on the PATH, so the MCP server was not registered. By hand:"
            Write-Host "  claude mcp add --scope user $name -- `"$java`" -jar `"$jar`""
        }
        if ($claudeSetUp) { $setUp += 'Claude Code' }
    }
    if (Get-Command codex -ErrorAction SilentlyContinue) { $found += 'Codex' }
    if (Get-Command agy -ErrorAction SilentlyContinue) { $found += 'Antigravity' }
    if (-not $NoCodex) {
        if (-not (Get-Command codex -ErrorAction SilentlyContinue)) {
            Write-Host "codex is not on the PATH, so Codex was left alone. Once it is installed:"
            Write-Host "  $javaCommand -jar `"$jar`" --install-codex"
        } else {
            if ($NoHooks) { $codexOptions += '--no-hooks' }
            & $java -jar $jar --install-codex @hookOptions @codexOptions
            if ($LASTEXITCODE -ne 0) { Write-Error "Codex could not be set up; see the message above." }
            Write-Host "Restart open Codex sessions to pick it up; the first one asks you to review the new hooks."
            $setUp += 'Codex'
        }
    }
    # Antigravity takes the same -PullAlways as Codex, and nothing about hooks: it
    # has none of Sheriff's.
    if (-not $NoAntigravity) {
        if (-not (Get-Command agy -ErrorAction SilentlyContinue)) {
            Write-Host "agy is not on the PATH, so Antigravity was left alone. Once it is installed:"
            Write-Host "  $javaCommand -jar `"$jar`" --install-antigravity"
        } else {
            $antigravityOptions = @()
            if ($PullAlways) { $antigravityOptions += '--pull-always' }
            & $java -jar $jar --install-antigravity @antigravityOptions
            if ($LASTEXITCODE -ne 0) { Write-Error "Antigravity could not be set up; see the message above." }
            Write-Host "Restart open agy sessions to pick it up."
            $setUp += 'Antigravity'
        }
    }
} finally {
    $env:SHERIFF_JAVA = $savedSheriffJava
}
# What every assistant set up will run, run once here: a server that does not
# start shows only "connection closed" once a session opens.
if ($setUp.Count -gt 0) {
    $failure = Test-Server
    if ($failure) {
        Write-Error "The MCP server does not start: FAIL. $failure"
    }
    Write-Host "The MCP server starts: OK, it answers as sheriff."
}
# Each assistant left out says so above, among everything else; this is the
# line that says what the install amounts to.
Write-Host ""
if ($setUp.Count -gt 0) {
    Write-Host "Sheriff is set up for $(Join-Names $setUp)."
} elseif ($found.Count -eq 0) {
    Write-Host "No assistant was found (Claude Code, Codex or Antigravity), so Sheriff is installed only as"
    Write-Host "the CI check and the Maven plugin:"
    Write-Host "  $javaCommand -jar `"$jar`" --check"
    Write-Host "Install one of them and run this again to set it up."
} else {
    Write-Host "Sheriff is set up for no assistant: the options left out $(Join-Names $found)."
}
