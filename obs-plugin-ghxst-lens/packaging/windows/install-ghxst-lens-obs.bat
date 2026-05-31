@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-ghxst-lens-obs.ps1" %*
pause
