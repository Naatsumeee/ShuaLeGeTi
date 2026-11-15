param(
    [string]$Owner = "Naatsumeee",
    [string]$Repo = "ShuaLeGeTi-release",
    [string]$Branch = "master",
    [string]$ManifestPath = ""
)

$ErrorActionPreference = "Stop"
$token = $env:GITEE_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    throw "请先设置 GITEE_TOKEN 环境变量"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $ManifestPath) {
    $ManifestPath = Join-Path $repoRoot "update.json"
}
$manifest = (Resolve-Path -LiteralPath $ManifestPath).Path
$api = "https://gitee.com/api/v5/repos/$Owner/$Repo/contents/update.json"
$current = Invoke-RestMethod -Method Get -Uri "$api`?access_token=$token&ref=$Branch"
$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($manifest))

Invoke-RestMethod -Method Put -Uri $api -Body @{
    access_token = $token
    content      = $encoded
    sha          = $current.sha
    message      = "更新 App 更新清单"
    branch       = $Branch
} | Out-Null

Write-Host "Synced $manifest to Gitee $Owner/$Repo@$Branch"
