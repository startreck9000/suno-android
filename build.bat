@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

:: ============================================================
:: Режим сборки:
::   onedir  - папка с exe, БЫСТРЫЙ запуск (рекомендуется)
::   onefile - один exe, МЕДЛЕННЫЙ запуск (распаковка 15-40 сек)
:: ============================================================
set MODE=onedir
set LOGFILE=%~dp0build.log

:: Начать лог
echo ============================================= > "%LOGFILE%"
echo   Suno MP3 Downloader - Build Log            >> "%LOGFILE%"
echo   Date: %date% %time%                        >> "%LOGFILE%"
echo ============================================= >> "%LOGFILE%"
echo.

echo ============================================================
echo   Suno MP3 Downloader - Build Script v4
echo ============================================================
echo        Log: build.log
echo.

:: Проверка: не запущен ли SunoDownloader.exe
tasklist /FI "IMAGENAME eq SunoDownloader.exe" 2>nul | find /I "SunoDownloader.exe" >nul
if not errorlevel 1 (
    echo [WARN] SunoDownloader.exe is RUNNING! Close it before building.
    echo [ERROR] SunoDownloader.exe is running - cannot delete dist folder >> "%LOGFILE%"
    echo.
    echo   Please close SunoDownloader.exe and run build.bat again.
    pause & exit /b 1
)

:: ----------------------------------------------------------
:: [1/8] Python check (x64 required)
:: ----------------------------------------------------------
echo [1/8] Checking Python (x64 required)...
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found in PATH. Install Python 3.10+ with "Add to PATH".
    echo [ERROR] Python not found >> "%LOGFILE%"
    pause & exit /b 1
)
for /f "tokens=*" %%v in ('python --version') do echo        %%v
echo [INFO] Python: for /f result >> "%LOGFILE%"
for /f "tokens=*" %%v in ('python --version') do echo [INFO] %%v >> "%LOGFILE%"

:: Check 64-bit Python
for /f "usebackq tokens=*" %%b in (`python -c "import struct; print('64' if struct.calcsize('P')==8 else '32')"`) do set PY_BITS=%%b
if "%PY_BITS%"=="32" (
    echo ERROR: 32-bit Python detected! This project targets Windows x64 only.
    echo        Install 64-bit Python: https://www.python.org/downloads/
    echo [ERROR] 32-bit Python detected >> "%LOGFILE%"
    pause & exit /b 1
)
echo        Architecture: %PY_BITS%-bit OK
echo [INFO] Python architecture: %PY_BITS%-bit >> "%LOGFILE%"

:: ----------------------------------------------------------
:: [2/8] Clean previous environment and artifacts
:: ----------------------------------------------------------
echo.
echo [2/8] Cleaning old environment and build artifacts...
if exist venv (
    echo        Removing old venv...
    rmdir /s /q venv
)
if exist build (
    rmdir /s /q build
)
if exist dist (
    rmdir /s /q dist
)
if exist SunoDownloader.spec (
    del /q SunoDownloader.spec
)
if exist __pycache__ (
    rmdir /s /q __pycache__
)
echo        Clean done.
echo [INFO] Clean done >> "%LOGFILE%"

:: ----------------------------------------------------------
:: [3/8] Create fresh venv + install dependencies
:: ----------------------------------------------------------
echo.
echo [3/8] Creating virtual environment + installing deps...
echo [INFO] Creating venv... >> "%LOGFILE%"
python -m venv venv || (echo Failed to create venv & echo [ERROR] venv creation failed >> "%LOGFILE%" & pause & exit /b 1)
call venv\Scripts\activate.bat
python -m pip install --upgrade pip >nul 2>&1
echo [INFO] Installing requirements... >> "%LOGFILE%"
pip install -r requirements.txt > "%TEMP%\pip_install.log" 2>&1
if errorlevel 1 (
    type "%TEMP%\pip_install.log"
    echo FAILED: pip install
    echo [ERROR] pip install failed >> "%LOGFILE%"
    pause & exit /b 1
)
type "%TEMP%\pip_install.log"
del "%TEMP%\pip_install.log" >nul 2>&1
echo        Dependencies installed.
echo [INFO] Dependencies installed OK >> "%LOGFILE%"

:: ----------------------------------------------------------
:: [4/8] Verify critical imports
:: ----------------------------------------------------------
echo.
echo [4/8] Verifying imports...
python -c "import customtkinter; print('        customtkinter OK')" || (echo FATAL: customtkinter & echo [ERROR] import customtkinter failed >> "%LOGFILE%" & pause & exit /b 1)
python -c "import requests; print('        requests OK')" || (echo FATAL: requests & echo [ERROR] import requests failed >> "%LOGFILE%" & pause & exit /b 1)
python -c "from Crypto.Cipher import AES; print('        pycryptodome OK')" || (echo FATAL: pycryptodome & echo [ERROR] import Crypto failed >> "%LOGFILE%" & pause & exit /b 1)
echo [INFO] All imports verified >> "%LOGFILE%"

:: ----------------------------------------------------------
:: [5/8] Check ffmpeg.exe (x64 required)
:: ----------------------------------------------------------
echo.
echo [5/8] Checking ffmpeg.exe...
if not exist ffmpeg.exe goto :ffmpeg_missing
for %%F in (ffmpeg.exe) do echo        ffmpeg.exe: %%~zF bytes
echo [INFO] ffmpeg.exe present >> "%LOGFILE%"

:: Verify ffmpeg.exe is 64-bit PE via pefile
python -c "import pefile; pe=pefile.PE('ffmpeg.exe'); m=pe.FILE_HEADER.Machine; assert m==0x8664, f'not x64'; print('        PE machine: x64 OK')" || goto :ffmpeg_32bit
echo        OK.
echo [INFO] ffmpeg.exe is x64 OK >> "%LOGFILE%"
goto :after_ffmpeg

:ffmpeg_missing
echo.
echo   ffmpeg.exe NOT FOUND in project root!
echo   Download from https://www.gyan.dev/ffmpeg/builds/
echo   Use ffmpeg-release-essentials.zip - 64-bit build
echo   Extract bin\ffmpeg.exe here.
echo [ERROR] ffmpeg.exe not found >> "%LOGFILE%"
echo.
pause & exit /b 1

:ffmpeg_32bit
echo ERROR: ffmpeg.exe is NOT a 64-bit binary!
echo        Download the x64 build: https://www.gyan.dev/ffmpeg/builds/
echo [ERROR] ffmpeg.exe is not x64 >> "%LOGFILE%"
pause & exit /b 1

:after_ffmpeg

:: ----------------------------------------------------------
:: [6/8] Check source files
:: ----------------------------------------------------------
echo.
echo [6/8] Checking source files...
set MISSING=0
for %%f in (main.py gui.py downloader.py utils.py logger.py) do (
    if not exist %%f (
        echo        MISSING: %%f
        set MISSING=1
    )
)
if %MISSING%==1 (
    echo   Source files missing! See list above.
    echo [ERROR] Source files missing >> "%LOGFILE%"
    pause & exit /b 1
)
echo        All source files found.
echo [INFO] All source files present >> "%LOGFILE%"

:: ----------------------------------------------------------
:: [7/8] Build with PyInstaller
:: ----------------------------------------------------------
echo.
echo [7/8] Building with PyInstaller...
echo [INFO] Mode: %MODE% >> "%LOGFILE%"

if "%MODE%"=="onefile" (
    echo        ONEFILE mode - single exe, slow start
    echo.
    pyinstaller --noconfirm --clean --onefile --windowed ^
        --name SunoDownloader ^
        --icon assets/icon.ico ^
        --add-binary "ffmpeg.exe;." ^
        --collect-submodules Crypto ^
        --hidden-import gui ^
        --hidden-import downloader ^
        --hidden-import utils ^
        --hidden-import logger ^
        main.py > "%TEMP%\pyinstaller.log" 2>&1
) else (
    echo        ONEDIR mode - folder with exe, fast start
    echo.
    pyinstaller --noconfirm --clean --onedir --windowed ^
        --name SunoDownloader ^
        --icon assets/icon.ico ^
        --add-binary "ffmpeg.exe;." ^
        --collect-submodules Crypto ^
        --hidden-import gui ^
        --hidden-import downloader ^
        --hidden-import utils ^
        --hidden-import logger ^
        main.py > "%TEMP%\pyinstaller.log" 2>&1
)

:: Вывод PyInstaller: на экран + в лог сборки
set PY_RC=%ERRORLEVEL%
type "%TEMP%\pyinstaller.log"
echo [INFO] === PyInstaller output === >> "%LOGFILE%"
type "%TEMP%\pyinstaller.log" >> "%LOGFILE%"
del "%TEMP%\pyinstaller.log" >nul 2>&1
if not %PY_RC%==0 (
    echo.
    echo BUILD FAILED.
    echo [ERROR] PyInstaller returned error >> "%LOGFILE%"
    echo.
    echo   Full log: build.log
    pause & exit /b 1
)

:: ----------------------------------------------------------
:: [8/8] Verify output
:: ----------------------------------------------------------
echo.
echo [8/8] Verifying build output...
if "%MODE%"=="onefile" (
    if not exist dist\SunoDownloader.exe (
        echo ERROR: dist\SunoDownloader.exe NOT FOUND!
        echo [ERROR] Output exe not found >> "%LOGFILE%"
        pause & exit /b 1
    )
    for %%F in (dist\SunoDownloader.exe) do set EXE_SIZE=%%~zF
    set /a SIZE_MB=!EXE_SIZE!/1048576
    echo        File: dist\SunoDownloader.exe
    echo        Size: !SIZE_MB! MB
    echo [OK] dist\SunoDownloader.exe = !SIZE_MB! MB >> "%LOGFILE%"
    echo.
    echo ============================================================
    echo   BUILD SUCCESSFUL!
    echo   Output: dist\SunoDownloader.exe
    echo   Size:   !SIZE_MB! MB
    echo   Log:    build.log
    echo ============================================================
) else (
    if not exist dist\SunoDownloader\SunoDownloader.exe (
        echo ERROR: dist\SunoDownloader\SunoDownloader.exe NOT FOUND!
        echo [ERROR] Output exe not found >> "%LOGFILE%"
        pause & exit /b 1
    )
    for %%F in (dist\SunoDownloader\SunoDownloader.exe) do set EXE_SIZE=%%~zF
    set /a SIZE_MB=!EXE_SIZE!/1048576
    echo        Folder: dist\SunoDownloader\
    echo        Main exe: SunoDownloader.exe (!SIZE_MB! MB)
    echo [OK] dist\SunoDownloader\SunoDownloader.exe = !SIZE_MB! MB >> "%LOGFILE%"
    echo.
    echo ============================================================
    echo   BUILD SUCCESSFUL!
    echo   Output: dist\SunoDownloader\  - whole folder
    echo   Launch: dist\SunoDownloader\SunoDownloader.exe
    echo   Start:  FAST - no unpacking delay
    echo   Log:    build.log
    echo.
    echo   To distribute: zip the dist\SunoDownloader folder
    echo   and send it. Recipient extracts and runs the exe.
    echo ============================================================
)

echo [OK] Build completed successfully >> "%LOGFILE%"
echo.
echo   Log saved to: build.log
echo.
pause
endlocal
