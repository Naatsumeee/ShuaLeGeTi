param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$GithubDownloadUrl,
    [string]$GiteeDownloadUrl = "",
    [string]$ReleaseUrl = "",
    [string]$NotesPath = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash

$notes = if ($NotesPath) {
    Get-Content -LiteralPath $NotesPath -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ } |
        Select-Object -First 6
} else {
    @()
}

$downloads = @($GiteeDownloadUrl, $GithubDownloadUrl) |
    Where-Object { $_ } |
    Select-Object -Unique

$manifest = [ordered]@{
    schema      = 1
    versionName = $VersionName
    versionCode = $VersionCode
    publishedAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    notes       = @($notes)
    downloads   = @($downloads)
    releaseUrl  = if ($ReleaseUrl) { $ReleaseUrl } else { $downloads[0] }
    sha256      = $sha256
}

$out = Join-Path $repoRoot "update.json"
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $out -Encoding utf8
Write-Host "Wrote $out"
Write-Host "SHA256 $sha256"
