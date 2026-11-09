#Requires -Version 5.1
[CmdletBinding(SupportsShouldProcess)]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$entryPoint = Join-Path $PSScriptRoot 'Manage-WasmDev.ps1'
$tokens = $null
$errors = $null
[Management.Automation.Language.Parser]::ParseFile($entryPoint, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count) { throw ($errors | Out-String) }
. $entryPoint

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('wasm-dev-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$RepoRoot = $testRoot
$InstallPath = Join-Path $testRoot 'WSL_image/wasm_audiveris_dev'
$VhdPath = Join-Path $InstallPath 'ext4.vhdx'
$RegistryRoot = Join-Path $testRoot 'fake-registry'
New-Item -ItemType Directory -Path $InstallPath, $RegistryRoot -Force | Out-Null
Set-Content -LiteralPath $VhdPath -Value 'Not a real disk'
$script:registrations = @()
$script:wslCalls = [Collections.Generic.List[object]]::new()
$script:deletions = [Collections.Generic.List[string]]::new()
$script:wslExit = 0
$script:checks = 0

function Get-ChildItem {
    param([string] $LiteralPath, [switch] $Force, [switch] $Recurse)
    if ($LiteralPath -eq $RegistryRoot) {
        for ($i = 0; $i -lt $script:registrations.Count; $i++) {
            [pscustomobject]@{ PSPath = "test-registry:$i" }
        }
    } else {
        Microsoft.PowerShell.Management\Get-ChildItem -LiteralPath $LiteralPath -Force:$Force -Recurse:$Recurse
    }
}
function Get-ItemProperty {
    param([string] $LiteralPath)
    $script:registrations[[int]($LiteralPath -replace '^test-registry:', '')]
}
function wsl.exe {
    $script:wslCalls.Add(@($args))
    $global:LASTEXITCODE = $script:wslExit
    if ($args[0] -eq '--unregister' -and $script:wslExit -eq 0) { $script:registrations = @() }
    if ($args -contains 'wslpath') { '/mnt/g/Windows source with spaces' }
}
function Remove-Item {
    param([string] $LiteralPath, [switch] $Recurse, [switch] $Force)
    $script:deletions.Add($LiteralPath)
}
function Get-SourceRevision { '0123456789012345678901234567890123456789-dirty' }
function Check {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:checks++
}
function Expect-Failure {
    param([scriptblock] $Body, [string] $Message)
    $failed = $false
    try { & $Body } catch { $failed = $true }
    Check $failed $Message
}
function New-Registration {
    param([string] $Path = $InstallPath, [string] $Name = $DistroName, [int] $Version = 2)
    [pscustomobject]@{ DistributionName = $Name; BasePath = $Path; Version = $Version }
}
function Run-Action {
    [CmdletBinding(SupportsShouldProcess)]
    param([string] $Name)
    $Action = $Name
    Invoke-Main
}

try {
    Assert-InstallPath
    Check ((Convert-RegistrationPath ('\\?\' + $InstallPath)) -eq $InstallPath) 'extended Windows path normalization'
    Assert-RegistrationPath (New-Registration)
    Expect-Failure { Assert-RegistrationPath (New-Registration -Path 'C:\unrelated') } 'reject wrong registration path'
    Expect-Failure { Assert-RegistrationPath (New-Registration -Version 1) } 'reject WSL1'

    $script:registrations = @(New-Registration -Path 'C:\unrelated')
    Expect-Failure { Run-Action Remove } 'wrong-path removal fails'
    Check ($script:wslCalls.Count -eq 0 -and $script:deletions.Count -eq 0) 'wrong-path removal has no side effects'

    $script:registrations = @(New-Registration -Name 'another_distro')
    Expect-Failure { Run-Action Remove } 'protect a disk registered under another name'
    Check ($script:deletions.Count -eq 0) 'other registration has no deletions'

    $script:registrations = @(New-Registration)
    Run-Action Remove -WhatIf
    Run-Action Install -WhatIf
    Run-Action Build -WhatIf
    Check ($script:wslCalls.Count -eq 0 -and $script:deletions.Count -eq 0) 'WhatIf makes no changes'

    $script:wslExit = 7
    Expect-Failure { Run-Action Remove } 'unregister errors propagate'
    Check ($script:deletions.Count -eq 0) 'do not delete disk after unregister failure'
    $script:wslExit = 0
    Run-Action Remove
    Check ($script:deletions.Count -eq 1 -and $script:deletions[0] -eq $InstallPath) 'delete only the dedicated directory'
    Check ($script:registrations.Count -eq 0) 'unregister the target'

    $script:deletions.Clear()
    $script:wslCalls.Clear()
    Run-Action Remove
    Check ($script:wslCalls.Count -eq 0 -and $script:deletions.Count -eq 1) 'remove unregistered leftovers without WSL calls'

    $script:registrations = @(New-Registration)
    $GradleArgs = @(':app:classes', '-Pexample=argument with spaces')
    Run-Action Build
    $buildCall = $script:wslCalls[$script:wslCalls.Count - 1]
    Check ($buildCall -contains '/mnt/g/Windows source with spaces/dev/wsl/mirror.sh') 'runner path remains a single argument'
    Check ($buildCall -contains '-Pexample=argument with spaces') 'Gradle arguments preserve spaces'
    Check ($buildCall -contains 'build') 'dispatch build mode'
    Check ($buildCall -contains '/mnt/g/Windows source with spaces/dev/wsl/run-limited.sh') 'sync/build use the resource limiter'

    Expect-Failure { Run-Action Run } 'Run requires an explicit command'
    $CommandArgs = @('printf', 'argument with spaces')
    Run-Action Run
    $runCall = $script:wslCalls[$script:wslCalls.Count - 1]
    Check ($runCall -contains 'printf' -and $runCall -contains 'argument with spaces') 'bounded Run preserves command arguments'

    $script:wslCalls.Clear()
    Run-Action Install
    $bootstrapCall = @($script:wslCalls | Where-Object { $_ -contains 'bash' })[0]
    Check ($bootstrapCall -contains 'root') 'bootstrap runs as root'
    Check ($bootstrapCall -contains '--as-root') 'bootstrap also uses resource limits'
    Check ($bootstrapCall -contains 'dev' -and $bootstrapCall -contains 'none') 'default user and optional SDK are explicit'
    $lastCall = $script:wslCalls[$script:wslCalls.Count - 1]
    Check ($lastCall[0] -eq '--terminate' -and $lastCall[1] -eq $DistroName) 'restart only the dedicated distro after bootstrap'

    $script:wslExit = 3
    Expect-Failure { Run-Action Sync } 'path translation failure stops dispatch'
    Write-Host "Passed $script:checks checks. No actual WSL operations or disk deletions were performed."
} finally {
    # Only the freshly created test directory may be removed, using native PowerShell.
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempParent, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notlike 'wasm-dev-tests-*') { throw 'Unsafe test cleanup path' }
    Microsoft.PowerShell.Management\Remove-Item -LiteralPath $resolved -Recurse -Force
}
