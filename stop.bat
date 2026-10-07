@echo off
title MultiMailSender - Durdur
echo MultiMailSender kapatiliyor...
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8080,4200 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"
taskkill /FI "WINDOWTITLE eq MMS Backend*" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq MMS Frontend*" /T /F >nul 2>&1
echo Tamam.
if not defined MMS_NOPAUSE timeout /t 3 >nul
