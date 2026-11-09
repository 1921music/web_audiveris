#Requires -Version 5.1
<#
.SYNOPSIS
Creates/removes the dedicated WSL2 environment, or synchronizes and builds its mirror.
.EXAMPLE
.\dev\wsl\Manage-WasmDev.ps1 Install
.EXAMPLE
.\dev\wsl\Manage-WasmDev.ps1 Build -GradleArgs @(':app:classes')
.EXAMPLE
.\dev\wsl\Manage-WasmDev.ps1 Remove -WhatIf
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'Medium')]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Install', 'Remove', 'Status', 'Sync', 'Build', 'Run')]
    [string] $Action = 'Status',

    [ValidatePattern('^[a-z][a-z0-9_-]{0,30}$')]
    [ValidateScript({ $_ -ne 'root' })]
    [string] $LinuxUser = 'dev',

    # Optional: explicitly select an emsdk release, e.g. -EmsdkVersion 4.0.20.
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $EmsdkVersion,

    [string[]] $GradleArgs = @(':app:classes'),

    [string[]] $CommandArgs = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$DistroName = 'wasm_audiveris_dev'
$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$InstallPath = [IO.Path]::GetFullPath((Join-Path $RepoRoot "WSL_image/$DistroName"))
$VhdPath = Join-Path $InstallPath 'ext4.vhdx'
$RegistryRoot = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss'

function Invoke-Wsl {
    param([string[]] $WslArgs)
    & wsl.exe @WslArgs
    if ($LASTEXITCODE -ne 0) { throw "wsl.exe failed (exit $LASTEXITCODE)." }
}

function Get-DistroRegistration {
    # Querying the registry also provides the path needed to guard unregister.
    if (Test-Path -LiteralPath $RegistryRoot) {
        foreach ($entry in Get-ChildItem -LiteralPath $RegistryRoot) {
            $registration = Get-ItemProperty -LiteralPath $entry.PSPath
            if ($registration.DistributionName -eq $DistroName) { $registration }
        }
    }
}

function Convert-RegistrationPath {
    param([string] $Path)
    if ($Path.StartsWith('\\?\')) { $Path = $Path.Substring(4) }
    return [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}

function Assert-NoOtherRegistration {
    if (Test-Path -LiteralPath $RegistryRoot) {
        foreach ($entry in Get-ChildItem -LiteralPath $RegistryRoot) {
            $registration = Get-ItemProperty -LiteralPath $entry.PSPath
            if ($registration.DistributionName -ne $DistroName -and
                (Convert-RegistrationPath $registration.BasePath) -eq $InstallPath.TrimEnd('\', '/')) {
                throw 'Another distribution uses this directory; refusing modification.'
            }
        }
    }
}

function Assert-InstallPath {
    # Refuse junctions/symlinks in every existing path component, including parents.
    $cursor = $InstallPath
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing a reparse point in the installation path: $cursor"
            }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    $expected = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'WSL_image/wasm_audiveris_dev'))
    if ($InstallPath -ne $expected -or [IO.Path]::GetFileName($InstallPath) -ne $DistroName) {
        throw 'Installation path is outside the dedicated directory.'
    }
}

function Assert-RegistrationPath {
    param($Registration)
    Assert-InstallPath
    $registeredPath = Convert-RegistrationPath $Registration.BasePath
    if ($registeredPath -ne $InstallPath.TrimEnd('\', '/')) {
        throw "The name $DistroName belongs to another location: $registeredPath"
    }
    if ($Registration.Version -ne 2) { throw 'The existing distribution must use WSL2.' }
}

function Get-LinuxPath {
    param([string] $WindowsPath)
    $result = & wsl.exe -d $DistroName -u root --exec wslpath -a -u $WindowsPath
    if ($LASTEXITCODE -ne 0) { throw "Could not translate path: $WindowsPath" }
    return (($result -join "`n").Trim())
}

function Get-SourceRevision {
    $revision = & git -C $RepoRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the Windows source revision.' }
    $changes = & git -C $RepoRoot status --porcelain
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the Windows source status.' }
    if ($changes) { $revision = "$revision-dirty" }
    return $revision
}

function Invoke-Mirror {
    param([string] $Mode)
    $sourcePath = Get-LinuxPath $RepoRoot
    $runner = "$sourcePath/dev/wsl/mirror.sh"
    $limiter = "$sourcePath/dev/wsl/run-limited.sh"
    $revision = Get-SourceRevision
    Invoke-Wsl -WslArgs (@('-d', $DistroName, '-u', $LinuxUser, '--exec',
        'bash', $limiter, 'bash', $runner, $Mode, $sourcePath, $revision) + $GradleArgs)
}

function Invoke-Main {
    Assert-InstallPath
    $registration = @(Get-DistroRegistration)
    if ($registration.Count -gt 1) { throw 'Multiple registrations have the target name.' }
    $existing = if ($registration.Count) { $registration[0] } else { $null }

    switch ($Action) {
        'Status' {
            if ($existing) { Assert-RegistrationPath $existing }
            [pscustomobject]@{
                Distribution = $DistroName
                Registered = [bool] $existing
                InstallPath = $InstallPath
                VhdExists = Test-Path -LiteralPath $VhdPath
                Source = $RepoRoot
            }
        }
        'Remove' {
            if ($existing) { Assert-RegistrationPath $existing }
            Assert-NoOtherRegistration
            if (-not $existing -and -not (Test-Path -LiteralPath $InstallPath)) {
                Write-Host 'The environment is already absent.'
                return
            }
            if (-not $PSCmdlet.ShouldProcess($InstallPath,
                "Permanently unregister $DistroName and delete its entire installation directory")) { return }
            if ($existing) {
                # unregister stops this distribution and deletes its managed disk.
                Invoke-Wsl -WslArgs @('--unregister', $DistroName)
                if (@(Get-DistroRegistration).Count) { throw 'Distribution is still registered; refusing file deletion.' }
            }
            Assert-InstallPath
            if (Test-Path -LiteralPath $InstallPath) {
                $links = Get-ChildItem -LiteralPath $InstallPath -Recurse -Force |
                    Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }
                if ($links) { throw 'Refusing recursive deletion of a directory containing reparse points.' }
                Remove-Item -LiteralPath $InstallPath -Recurse -Force
            }
        }
        'Install' {
            if ($existing) { Assert-RegistrationPath $existing }
            Assert-NoOtherRegistration
            if (-not $PSCmdlet.ShouldProcess($InstallPath, "Install/configure Ubuntu 24.04 LTS as $DistroName")) { return }
            Get-Command wsl.exe -ErrorAction Stop | Out-Null
            Invoke-Wsl -WslArgs @('--list', '--quiet')
            if (-not $existing) {
                if ((Test-Path -LiteralPath $InstallPath) -and
                    @(Get-ChildItem -LiteralPath $InstallPath -Force).Count) {
                    throw 'The unregistered installation directory is not empty. Inspect it before using Remove.'
                }
                if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
                    throw 'This image supports x64 Windows only.'
                }
                $cachePath = Join-Path $RepoRoot 'WSL_image/downloads'
                New-Item -ItemType Directory -Path $cachePath -Force | Out-Null
                # Pin the base image and its published SHA256; downloads remain available for retry.
                $imageName = 'ubuntu-24.04.5-wsl-amd64.wsl'
                $imageHash = 'bb415d824822c4b878125729af451a5d18fb13d1cf5cbed9a7393ad64ac6039e'
                $imagePath = Join-Path $cachePath $imageName
                if (-not (Test-Path -LiteralPath $imagePath) -or
                    (Get-FileHash -LiteralPath $imagePath -Algorithm SHA256).Hash -ne $imageHash) {
                    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                    Invoke-WebRequest -UseBasicParsing -Uri "https://releases.ubuntu.com/24.04/$imageName" -OutFile $imagePath
                }
                if ((Get-FileHash -LiteralPath $imagePath -Algorithm SHA256).Hash -ne $imageHash) {
                    throw 'Ubuntu image checksum mismatch; refusing import.'
                }
                New-Item -ItemType Directory -Path $InstallPath -Force | Out-Null
                Invoke-Wsl -WslArgs @('--import', $DistroName, $InstallPath, $imagePath, '--version', '2')
                $imported = @(Get-DistroRegistration)
                if ($imported.Count -ne 1) { throw 'Cannot verify imported distribution.' }
                Assert-RegistrationPath $imported[0]
            }
            if (-not (Test-Path -LiteralPath $VhdPath)) { throw "Expected VHDX is missing: $VhdPath" }
            $bootstrap = Get-LinuxPath (Join-Path $PSScriptRoot 'bootstrap.sh')
            $limiter = Get-LinuxPath (Join-Path $PSScriptRoot 'run-limited.sh')
            $sdk = if ($EmsdkVersion) { $EmsdkVersion } else { 'none' }
            Invoke-Wsl -WslArgs @('-d', $DistroName, '-u', 'root', '--exec', 'bash', $limiter, '--as-root', 'bash', $bootstrap, $LinuxUser, $sdk)
            Invoke-Wsl -WslArgs @('--terminate', $DistroName)
            Write-Host "Ready: wsl -d $DistroName"
            Write-Host "VHDX: $VhdPath"
        }
        { $_ -in 'Sync', 'Build' } {
            if (-not $existing) { throw 'Run Install first.' }
            Assert-RegistrationPath $existing
            if ($PSCmdlet.ShouldProcess($DistroName, "$Action the Linux build mirror from Windows sources")) {
                Invoke-Mirror $Action.ToLowerInvariant()
            }
        }
        'Run' {
            if (-not $existing) { throw 'Run Install first.' }
            Assert-RegistrationPath $existing
            if ($CommandArgs.Count -eq 0) { throw 'Run requires -CommandArgs.' }
            if ($PSCmdlet.ShouldProcess($DistroName, 'Run a command with CPU and memory limits')) {
                $limiter = Get-LinuxPath (Join-Path $PSScriptRoot 'run-limited.sh')
                Invoke-Wsl -WslArgs (@('-d', $DistroName, '-u', $LinuxUser, '--exec', 'bash', $limiter) + $CommandArgs)
            }
        }
    }
}

# Allow the guard functions to be tested without executing any action.
if ($MyInvocation.InvocationName -ne '.') { Invoke-Main }
