@echo off
rem QUTC dev launcher: compile if needed, then start without console (javaw).
setlocal
set "TOOLS=C:\qutc-toolchain"
set "PROJ=%~dp0.."
set "DATA=%PROJ%\..\_mc-data"
set "JAVAW=%TOOLS%\jdk21\bin\javaw.exe"

cd /d "%PROJ%"
if not exist "target\classes\com\qutc\launcher\main\Launcher.class" (
    echo [QUTC] classes missing, compiling...
    call "%TOOLS%\maven\bin\mvn.cmd" -s "%TOOLS%\maven-settings.xml" -o -q compile
    if errorlevel 1 (
        echo [QUTC] compile failed. Run mvn manually to see the error.
        pause
        exit /b 1
    )
)

start "" "%JAVAW%" -Dfile.encoding=UTF-8 "-Dqutc.home=%DATA%" -cp "target\classes;target\lib\*" com.qutc.launcher.main.Launcher
exit /b 0
