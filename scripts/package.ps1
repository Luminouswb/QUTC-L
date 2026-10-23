# QUTC 打包：jpackage 生成 app-image / exe / msi
#
# 用法（执行策略受限时）：
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -Type app-image
#
# 注意：工具链必须在工作区外（见 env.ps1），否则 jpackage 会被沙箱判定目标目录不可写。
param(
    [ValidateSet('app-image', 'exe', 'msi', 'installers')]
    [string]$Type = 'app-image',

    [string]$AppVersion = '0.1.0'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$project = Split-Path -Parent $PSScriptRoot
Push-Location $project
try {
    $jar = Join-Path $project 'target\qutc-launcher.jar'
    $lib = Join-Path $project 'target\lib'
    if (-not (Test-Path $jar)) {
        throw '未找到 target\qutc-launcher.jar，请先运行 build.ps1'
    }

    # jpackage 的 --input 必须是单一目录，主 jar 与全部依赖同放
    $input = Join-Path $project 'target\package-input'
    if (Test-Path $input) { Remove-Item $input -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $input | Out-Null
    Copy-Item (Join-Path $lib '*') $input -Force
    Copy-Item $jar $input -Force

    $icon = Join-Path $project 'build\resources\QUTC.ico'

    $common = @(
        '--name', 'QUTC',
        '--app-version', $AppVersion,
        '--input', $input,
        '--main-jar', 'qutc-launcher.jar',
        '--main-class', 'com.qutc.launcher.main.Launcher',
        '--vendor', 'QUTC',
        '--description', 'QUTC Minecraft Launcher',
        '--copyright', 'Copyright (c) 2026 QUTC',
        '--java-options', '-Dfile.encoding=UTF-8',
        '--java-options', '-Xmx512m'
    )
    if (Test-Path $icon) {
        $common += @('--icon', $icon)
    }

    $winOptions = @(
        '--win-menu',
        '--win-shortcut',
        '--win-dir-chooser',
        '--win-menu-group', 'QUTC',
        '--win-upgrade-uuid', '6f3a1c2e-8b47-4d59-9c11-7a2d5e6f8a90'
    )

    function Invoke-JPackage {
        param([string]$Kind, [string[]]$Extra)
        $dest = Join-Path $project ("target\dist-" + $Kind)
        if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        $argv = @('--type', $Kind, '--dest', $dest) + $common + $Extra
        Write-Host "[package] jpackage --type $Kind" -ForegroundColor Cyan
        & jpackage @argv
        if ($LASTEXITCODE -ne 0) { throw "jpackage --type $Kind 失败，退出码 $LASTEXITCODE" }
        foreach ($item in Get-ChildItem $dest) {
            if ($item.PSIsContainer) {
                Write-Host ('  -> ' + $item.Name + '  <目录>')
            } else {
                Write-Host ('  -> ' + $item.Name + '  ' + ('{0:N1} MB' -f ($item.Length / 1MB)))
            }
        }
        return $dest
    }

    if ($Type -eq 'app-image') {
        Invoke-JPackage -Kind 'app-image' -Extra @() | Out-Null
    } else {
        $wix = $env:QUTC_WIX
        if (-not (Test-Path (Join-Path $wix 'candle.exe'))) {
            throw "未找到 WiX: $wix\candle.exe"
        }
        $env:PATH = "$wix;$env:PATH"
        Write-Host "[package] WiX = $wix" -ForegroundColor DarkGray

        if ($Type -eq 'exe' -or $Type -eq 'installers') {
            Invoke-JPackage -Kind 'exe' -Extra $winOptions | Out-Null
        }
        if ($Type -eq 'msi' -or $Type -eq 'installers') {
            Invoke-JPackage -Kind 'msi' -Extra $winOptions | Out-Null
        }
    }
    Write-Host '[package] 完成' -ForegroundColor Green
} finally {
    Pop-Location
}
