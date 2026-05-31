@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall-ghxst-lens-obs.ps1" %*
pause
