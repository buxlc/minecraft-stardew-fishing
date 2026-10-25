param(
    [switch]$Build,
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$MavenHome,
    [string]$Repository = 'F:\codex-cache\maven',
    [string]$TempRoot = 'F:\Temp\StardewFishing'
)
$ErrorActionPreference = 'Stop'
$originalTemp = $env:TEMP
$originalTmp = $env:TMP
try {
New-Item -ItemType Directory -Path $TempRoot -Force | Out-Null
$env:TEMP = $TempRoot
$env:TMP = $TempRoot
if ($Build) {
    $buildArgs = @{ JdkHome = $JdkHome; Repository = $Repository; TempRoot = $TempRoot }
    if ($MavenHome) { $buildArgs.MavenHome = $MavenHome }
    & (Join-Path $PSScriptRoot 'build.ps1') @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
}
$jarPath = Join-Path $PSScriptRoot 'target\StardewFishing-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先执行 build.ps1 完成编译与测试。' }
$distPath = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Path $distPath -Force | Out-Null
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $distPath 'StardewFishing-1.0.0.jar') -Force
Compress-Archive -LiteralPath (Join-Path $PSScriptRoot 'src'),(Join-Path $PSScriptRoot 'pom.xml'),(Join-Path $PSScriptRoot 'build.ps1'),(Join-Path $PSScriptRoot 'package.ps1'),(Join-Path $PSScriptRoot 'README.md'),(Join-Path $PSScriptRoot 'VERIFICATION.md'),(Join-Path $PSScriptRoot '.gitignore'),(Join-Path $PSScriptRoot 'LICENSE') -DestinationPath (Join-Path $distPath 'StardewFishing-1.0.0-source.zip') -Force
$checksums = Get-FileHash -LiteralPath (Join-Path $distPath 'StardewFishing-1.0.0.jar'),(Join-Path $distPath 'StardewFishing-1.0.0-source.zip') -Algorithm SHA256
$checksums | ForEach-Object { '{0}  {1}' -f $_.Hash, [IO.Path]::GetFileName($_.Path) } | Set-Content -LiteralPath (Join-Path $distPath 'StardewFishing-1.0.0-SHA256.txt') -Encoding utf8
Write-Output ('交付文件已生成：' + $distPath)
} finally {
    $env:TEMP = $originalTemp
    $env:TMP = $originalTmp
}
