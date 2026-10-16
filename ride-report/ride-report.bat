@echo off
rem KawaNav ride report: drag a ride's CSV onto this file, or run it from a command prompt.
rem Needs Python 3.9+ on the PATH only.
setlocal
where python >nul 2>nul
if errorlevel 1 (
  echo Python was not found on the PATH. Install it from https://www.python.org/downloads/ and try again.
  pause
  exit /b 1
)
python "%~dp0ride_report.py" --open %*
if errorlevel 1 pause
endlocal
