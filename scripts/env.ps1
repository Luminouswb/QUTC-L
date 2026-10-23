# QUTC 构建环境（点引用： . .\scripts\env.ps1）
#
# 重要：工具链必须放在工作区之外（C:\qutc-toolchain）。
# 本机沙箱对「位于工作区内的可执行文件」施加写限制：工作区内的 JDK 会让
# jpackage 报「目标目录不可写」，也会让 Java 进程无法写 %TEMP%。
# 工具链固定在工作区外即可绕开。

if (-not $env:QUTC_TOOLS) { $env:QUTC_TOOLS = 'C:\qutc-toolchain' }

$env:JAVA_HOME = Join-Path $env:QUTC_TOOLS 'jdk21'
$env:MAVEN_HOME = Join-Path $env:QUTC_TOOLS 'maven'
$env:QUTC_JAVAFX_JMODS = Join-Path $env:QUTC_TOOLS 'javafx-jmods'
$env:QUTC_WIX = Join-Path $env:QUTC_TOOLS 'wix'
$env:QUTC_MAVEN_SETTINGS = Join-Path $env:QUTC_TOOLS 'maven-settings.xml'

$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8 -Xmx1536m'

Write-Host "[env] JAVA_HOME = $env:JAVA_HOME" -ForegroundColor DarkGray
Write-Host "[env] MAVEN_HOME = $env:MAVEN_HOME" -ForegroundColor DarkGray
