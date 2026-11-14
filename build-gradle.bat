@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ==============================================
echo   FinalBreachWinter - збірка через Gradle
echo ==============================================
echo.

set GRADLE=gradlew.bat
if not exist gradlew.bat set GRADLE=gradle

where %GRADLE% >nul 2>nul
if errorlevel 1 (
    echo [ПОМИЛКА] Gradle не знайдено. Встанови Gradle 9+ і додай у PATH:
    echo https://gradle.org/install/
    echo Або один раз виконай "gradle wrapper" - тоді з'явиться gradlew.bat.
    goto :fail
)

echo Потрібна Java 25 ^(JDK^); Gradle сам її знайде або завантажить.
echo.
call %GRADLE% clean build
if errorlevel 1 (
    echo.
    echo [ПОМИЛКА] Збірка не вдалась. Дивись повідомлення вище.
    goto :fail
)

if not exist "out" mkdir "out"
copy /y "build\libs\FinalBreachWinter-*.jar" "out\" >nul
echo.
echo [ГОТОВО] Плагін у папці out\
echo Поклади jar у plugins свого Paper 26.3 сервера.
echo.
pause
exit /b 0

:fail
echo.
pause
exit /b 1
