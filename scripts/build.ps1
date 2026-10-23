# QUTC 编译打包：mvn clean package
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

Push-Location (Join-Path $PSScriptRoot '..')
try {
    $mvnArgs = @('-s', $env:QUTC_MAVEN_SETTINGS, 'clean', 'package', '-B')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    & mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败，退出码 $LASTEXITCODE" }
    Write-Host "[build] 构建成功" -ForegroundColor Green
} finally {
    Pop-Location
}
