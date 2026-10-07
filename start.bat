@echo off
setlocal EnableExtensions EnableDelayedExpansion
title MultiMailSender - Kurulum ve Baslatma
cd /d "%~dp0"
set "ROOT=%~dp0"
set "BACKEND=%ROOT%backend\MultiMailSender"
set "FRONTEND=%ROOT%frontend\multi-mail-sender"

echo.
echo  ==========================================
echo    MultiMailSender - otomatik kurulum
echo  ==========================================
echo.

rem ---------- 1) Java 17+ ----------
echo [1/5] Java kontrol ediliyor...
set "JAVA_HOME="
for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\find-java.ps1"`) do set "JAVA_HOME=%%J"
if not defined JAVA_HOME (
    echo       Java 17+ bulunamadi, winget ile kuruluyor ^(Temurin 17^)...
    where winget >nul 2>&1
    if errorlevel 1 goto :no_winget_java
    winget install -e --id EclipseAdoptium.Temurin.17.JDK --accept-source-agreements --accept-package-agreements
    for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\find-java.ps1"`) do set "JAVA_HOME=%%J"
)
if not defined JAVA_HOME goto :no_java
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo       Java: %JAVA_HOME%

rem ---------- 2) Node.js 18+ ----------
echo [2/5] Node.js kontrol ediliyor...
call :check_node
if "%NODE_OK%"=="0" (
    echo       Node.js 18+ bulunamadi, winget ile kuruluyor ^(LTS^)...
    where winget >nul 2>&1
    if errorlevel 1 goto :no_winget_node
    winget install -e --id OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements
    if exist "%ProgramFiles%\nodejs\node.exe" set "PATH=%ProgramFiles%\nodejs;%PATH%"
    call :check_node
)
if "%NODE_OK%"=="0" goto :no_node
echo       Node.js hazir.

rem ---------- port kontrolu ----------
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
    echo.
    echo  [!] 8080 portu zaten kullanimda. Calisan bir MultiMailSender varsa once stop.bat calistirin.
    goto :fail
)
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 4200 -State Listen -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
    echo.
    echo  [!] 4200 portu zaten kullanimda. Calisan bir MultiMailSender varsa once stop.bat calistirin.
    goto :fail
)

rem ---------- 3) Backend derleme ----------
echo [3/5] Backend derleniyor ^(ilk seferde bagimliliklar indirilir, birkac dakika surebilir^)...
pushd "%BACKEND%"
call "%BACKEND%\mvnw.cmd" -q -B -DskipTests package
if errorlevel 1 (
    popd
    echo.
    echo  [!] Backend derlenemedi. Yukaridaki hata mesajina bakin.
    goto :fail
)
set "JAR="
for %%F in ("%BACKEND%\target\MultiMailSender-*.jar") do set "JAR=%%F"
popd
if not defined JAR (
    echo  [!] Derlenen jar dosyasi bulunamadi.
    goto :fail
)

rem ---------- 4) Frontend bagimliliklari ----------
echo [4/5] Frontend bagimliliklari yukleniyor...
pushd "%FRONTEND%"
call npm install --no-audit --no-fund
if errorlevel 1 (
    popd
    echo.
    echo  [!] npm install basarisiz oldu.
    goto :fail
)
popd

rem ---------- 5) Baslat ----------
echo [5/5] Uygulama baslatiliyor...
start "MMS Backend" /D "%BACKEND%" cmd /k ""%JAVA_HOME%\bin\java.exe" -jar "%JAR%""
start "MMS Frontend" /D "%FRONTEND%" cmd /k "npm start"

echo       Sunucularin hazir olmasi bekleniyor ^(en fazla 3 dk^)...
powershell -NoProfile -Command "$a=$false;$b=$false;for($i=0;$i -lt 90;$i++){ if(-not $a){try{Invoke-WebRequest 'http://localhost:4200' -UseBasicParsing -TimeoutSec 2 | Out-Null;$a=$true}catch{}}; if(-not $b){try{if((Invoke-RestMethod 'http://localhost:8080/health' -TimeoutSec 2).message){$b=$true}}catch{}}; if($a -and $b){exit 0}; Start-Sleep 2 }; exit 1"
if errorlevel 1 (
    echo.
    echo  [!] Sunucular zamaninda hazir olmadi. "MMS Backend" ve "MMS Frontend" pencerelerindeki hatalara bakin.
    goto :fail
)

echo.
echo  ==========================================
echo    HAZIR!  http://localhost:4200
echo    Kapatmak icin stop.bat calistirin.
echo  ==========================================
if not defined MMS_NOBROWSER start "" "http://localhost:4200"
if not defined MMS_NOPAUSE timeout /t 8 >nul
exit /b 0

:check_node
set "NODE_OK=0"
where node >nul 2>&1
if errorlevel 1 exit /b 0
for /f "tokens=1 delims=." %%a in ('node -v') do set "NV=%%a"
set "NV=%NV:v=%"
if %NV% GEQ 18 set "NODE_OK=1"
exit /b 0

:no_winget_java
echo.
echo  [!] Java 17+ gerekli ve winget bulunamadi. Su adresten Temurin 17 JDK kurup bu dosyayi tekrar calistirin:
echo      https://adoptium.net/temurin/releases/?version=17
goto :fail

:no_winget_node
echo.
echo  [!] Node.js 18+ gerekli ve winget bulunamadi. Su adresten LTS surumunu kurup bu dosyayi tekrar calistirin:
echo      https://nodejs.org/
goto :fail

:no_java
echo.
echo  [!] Java 17+ kurulamadi. https://adoptium.net/temurin/releases/?version=17 adresinden elle kurup tekrar deneyin.
goto :fail

:no_node
echo.
echo  [!] Node.js kurulamadi. https://nodejs.org/ adresinden elle kurup tekrar deneyin.
goto :fail

:fail
echo.
if not defined MMS_NOPAUSE pause
exit /b 1
