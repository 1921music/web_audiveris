# Publish already-built, verified assets without switching the source checkout.
[CmdletBinding()]
param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$packageRoot = Join-Path $repoRoot 'wsl-artifacts/wasm-engine'
$offlineRoot = Join-Path $repoRoot 'wsl-artifacts/wasm-engine-offline'
function Invoke-RepoGit([string[]]$GitArguments) {
    & git -C $repoRoot -c pack.threads=8 -c pack.windowMemory=64m -c pack.deltaCacheSize=64m @GitArguments
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $($GitArguments[0]) (exit $LASTEXITCODE)" }
}
function Read-Json([string]$Path) { Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Package-File([string]$Name) {
    $path = [IO.Path]::GetFullPath((Join-Path $packageRoot $Name))
    if (-not $path.StartsWith($packageRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Asset path escapes package: $Name"
    }
    if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked asset: $Name" }
    return $path
}
$origin = Invoke-RepoGit @('remote', 'get-url', 'origin')
if ($origin -ne 'https://github.com/1921music/web_audiveris.git') { throw 'Unexpected origin repository.' }
$sourceCommit = Invoke-RepoGit @('rev-parse', 'HEAD')
$ocr = Read-Json (Join-Path $packageRoot 'ocr/assets.json')
if ($ocr.backend -ne 'tesseract-wasm') { throw 'The application Pages build must use tesseract-wasm.' }
$scratch = Join-Path $repoRoot ('build/pages/' + [Guid]::NewGuid().ToString('N'))
$site = Join-Path $scratch 'site'
New-Item -ItemType Directory -Path $site -Force | Out-Null
$entries = [ordered]@{}
foreach ($manifestName in @('integrity.json', 'ocr/integrity.json')) {
    $prefix = if ($manifestName.StartsWith('ocr/')) { 'ocr/' } else { '' }
    $manifest = Read-Json (Package-File $manifestName)
    foreach ($entry in $manifest.PSObject.Properties) {
        $name = $prefix + $entry.Name
        $inputFile = Package-File $name
        if ((Get-Item -LiteralPath $inputFile).Length -ne $entry.Value.bytes -or (Hash $inputFile) -ne $entry.Value.sha256) {
            throw "Asset differs from integrity manifest: $name"
        }
        $entries[$name] = $inputFile
    }
    $entries[$manifestName] = Package-File $manifestName
}
# Only manifest-listed runtime assets are published, never test inputs or build caches.
foreach ($entry in $entries.GetEnumerator()) {
    $target = Join-Path $site $entry.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $entry.Value -Destination $target
}
$offline = Read-Json (Join-Path $offlineRoot 'build.json')
$offlineHtml = Join-Path $offlineRoot 'audiveris-offline.html'
if ($offline.ocrBackend -ne 'tesseract-wasm' -or (Hash $offlineHtml) -ne $offline.htmlSha256) { throw 'Offline build is not the verified LSTM artifact.' }
Copy-Item -LiteralPath $offlineHtml -Destination (Join-Path $site 'audiveris-offline.html')
[IO.File]::WriteAllText((Join-Path $site '.nojekyll'), '')
$deployment = [ordered]@{
    sourceCommit = $sourceCommit
    backend = $ocr.backend
    publishedAt = [DateTime]::UtcNow.ToString('o')
    url = 'https://1921music.github.io/web_audiveris/'
    engineSha256 = Hash (Package-File 'engine.wasm')
    offlineHtmlSha256 = $offline.htmlSha256
    versions = $ocr.versions
}
[IO.File]::WriteAllText((Join-Path $site 'deployment.json'), (ConvertTo-Json $deployment -Depth 6) + "`n", [Text.UTF8Encoding]::new($false))
if ($PrepareOnly) {
    [PSCustomObject]@{site=$site;backend=$ocr.backend;sourceCommit=$sourceCommit;files=$entries.Count+3} | ConvertTo-Json
    return
}
if ((Invoke-RepoGit @('branch', '--show-current')) -ne 'wasm') { throw 'Publish from the wasm source branch.' }
if (Invoke-RepoGit @('status', '--porcelain')) { throw 'Commit source changes before publishing.' }
$parent = $null
$remote = Invoke-RepoGit @('ls-remote', '--heads', 'origin', 'refs/heads/gh-pages')
if ($remote) {
    Invoke-RepoGit @('fetch', 'origin', 'refs/heads/gh-pages') | Out-Host
    $parent = Invoke-RepoGit @('rev-parse', 'FETCH_HEAD')
}
& git -C $repoRoot show-ref --verify --quiet refs/heads/gh-pages
if ($LASTEXITCODE -eq 0) {
    $local = Invoke-RepoGit @('rev-parse', 'refs/heads/gh-pages')
    if ($local -ne $parent) { throw 'Local gh-pages contains unpublished changes; inspect it before publishing.' }
}
$previousIndex = $env:GIT_INDEX_FILE
try {
    $env:GIT_INDEX_FILE = Join-Path $scratch 'index'
    Invoke-RepoGit @('read-tree', '--empty') | Out-Host
    Invoke-RepoGit @('-c', 'core.autocrlf=false', '--work-tree', $site, 'add', '--force', '--all', '--', $site) | Out-Host
    $tree = Invoke-RepoGit @('write-tree')
    $commitArguments = @('commit-tree', $tree, '-m', "Publish tesseract-wasm application from $sourceCommit")
    if ($parent) { $commitArguments += @('-p', $parent) }
    $commit = Invoke-RepoGit $commitArguments
    Invoke-RepoGit @('update-ref', 'refs/heads/gh-pages', $commit) | Out-Host
} finally {
    if ($null -eq $previousIndex) { Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue }
    else { $env:GIT_INDEX_FILE = $previousIndex }
}
Invoke-RepoGit @('push', 'origin', 'gh-pages:gh-pages') | Out-Host
[PSCustomObject]@{commit=$commit;sourceCommit=$sourceCommit;backend=$ocr.backend;url=$deployment.url;site=$site} | ConvertTo-Json
