param(
    [string]$Owner = "Naatsumeee",
    [string]$Repo = "ShuaLeGeTi-release",
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$NotesPath,
    [string]$TargetBranch = "master"
)

$ErrorActionPreference = "Stop"
$token = $env:GITEE_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    throw "请先设置 GITEE_TOKEN 环境变量"
}

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$body = Get-Content -LiteralPath $NotesPath -Raw -Encoding UTF8
$release = Invoke-RestMethod `
    -Method Post `
    -Uri "https://gitee.com/api/v5/repos/$Owner/$Repo/releases" `
    -Body @{
        access_token     = $token
        tag_name         = $Tag
        name             = $Tag
        body             = $body
        target_commitish = $TargetBranch
    }

$response = & curl.exe --silent --show-error --fail-with-body `
    -X POST `
    -F "access_token=$token" `
    -F "file=@$apk" `
    "https://gitee.com/api/v5/repos/$Owner/$Repo/releases/$($release.id)/attach_files"
if ($LASTEXITCODE -ne 0) {
    throw "Gitee 附件上传失败，curl 退出码 $LASTEXITCODE"
}
$asset = $response | ConvertFrom-Json

Write-Host "Gitee release: https://gitee.com/$Owner/$Repo/releases/tag/$Tag"
Write-Host "Gitee APK: $($asset.browser_download_url)"
