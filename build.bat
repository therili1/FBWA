@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ==============================================
echo   FinalBreachWinter - збірка (Paper 26.3)
echo ==============================================
echo.

where java >nul 2>nul
if errorlevel 1 (
    echo [ПОМИЛКА] Java не знайдено. Потрібна Java 25 ^(JDK^).
    echo Завантаж: https://adoptium.net
    goto :fail
)

where mvn >nul 2>nul
if errorlevel 1 (
    echo [ПОМИЛКА] Maven не знайдено. Встанови Maven і додай його в PATH:
    echo https://maven.apache.org/download.cgi
    goto :fail
)

echo Java:
java -version
echo.
echo Збираю плагін ^(потрібен інтернет для завантаження paper-api^)...
echo.

call mvn -B clean package
if errorlevel 1 (
    echo.
    echo [ПОМИЛКА] Збірка не вдалась. Дивись повідомлення вище.
    goto :fail
)

if not exist "out" mkdir "out"
set FOUND=
for %%f in (target\*.jar) do (
    echo %%~nf | findstr /i "original sources javadoc" >nul
    if errorlevel 1 (
        copy /y "%%f" "out\" >nul
        set FOUND=%%~nxf
    )
)

echo.
if defined FOUND (
    echo [ГОТОВО] Плагін: out\%FOUND%
    echo Поклади його в папку plugins свого Paper 26.3 сервера.
) else (
    echo [ПОМИЛКА] Jar-файл не знайдено в target\.
    goto :fail
)

echo.
pause
exit /b 0

:fail
echo.
pause
exit /b 1
