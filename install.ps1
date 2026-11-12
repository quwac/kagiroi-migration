param(
    [string]$Version = $env:KAGIROI_VERSION,
    [string]$InstallDir = $env:KAGIROI_INSTALL_DIR,
    [switch]$NoModifyPath
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') {
    throw 'Use install.sh for macOS or Linux.'
}
if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'The Windows CLI supports x64.'
}
if (-not $InstallDir) {
    $InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\Kagiroi\bin'
}
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
$repository = 'https://github.com/quwac/kagiroi-migration'
if (-not $Version) {
    $release = Invoke-RestMethod 'https://api.github.com/repos/quwac/kagiroi-migration/releases/latest'
    $Version = $release.tag_name
}
$Version = $Version -replace '^v', ''
if ($Version -notmatch '^\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?$') {
    throw 'Invalid version.'
}
$name = "kagiroi-$Version-windows-x64"
$archiveName = "$name.zip"
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
$staged = $null
try {
    New-Item -ItemType Directory -Path $temporary | Out-Null
    $archive = Join-Path $temporary $archiveName
    $download = "$repository/releases/download/v$Version/$archiveName"
    Invoke-WebRequest -UseBasicParsing -Uri $download -OutFile $archive
    $checksumFile = Join-Path $temporary 'checksum'
    Invoke-WebRequest -UseBasicParsing -Uri "$download.sha256" -OutFile $checksumFile
    $checksum = (Get-Content -Raw $checksumFile).Trim()
    if ($checksum -notmatch ('^([0-9a-fA-F]{64})\s+' + [regex]::Escape($archiveName) + '$')) {
        throw 'Invalid SHA-256 checksum file.'
    }
    $expected = $Matches[1]
    if ((Get-FileHash -Algorithm SHA256 $archive).Hash -ne $expected) {
        throw 'SHA-256 checksum mismatch; nothing was installed.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $temporary
    $source = Join-Path $temporary "$name\kagiroi.exe"
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw 'The archive has no CLI executable.'
    }
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $staged = Join-Path $InstallDir ('.kagiroi-install-' + [System.Guid]::NewGuid().ToString() + '.exe')
    Copy-Item -LiteralPath $source -Destination $staged
    Move-Item -Force -LiteralPath $staged -Destination (Join-Path $InstallDir 'kagiroi.exe')
    if (-not $NoModifyPath) {
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if (($userPath -split ';') -notcontains $InstallDir) {
            $newPath = if ($userPath) { "$userPath;$InstallDir" } else { $InstallDir }
            [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        }
        if (($env:Path -split ';') -notcontains $InstallDir) {
            $env:Path = "$env:Path;$InstallDir"
        }
    }
    Write-Output "Installed kagiroi $Version to $InstallDir\kagiroi.exe"
} finally {
    if ($staged -and (Test-Path -LiteralPath $staged)) {
        Remove-Item -Force -LiteralPath $staged
    }
    Remove-Item -Recurse -Force -LiteralPath $temporary -ErrorAction SilentlyContinue
}
