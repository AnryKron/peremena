@echo off
rem ============================================================
rem  «Перемена» - сборка APK на Windows 10/11
rem  Нужно: Android Studio (в нём есть Java и Android SDK).
rem  Запуск: двойной щелчок по этому файлу в папке проекта.
rem ============================================================
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"
title Сборка APK «Перемена»

echo.
echo  ==== Сборка APK «Перемена» ====
echo.

if not exist "gradlew.bat" (
  echo  [ОШИБКА] Рядом нет gradlew.bat. Положите этот файл в корень проекта.
  goto :fail
)

rem ---------- 1. Java 17+ ----------
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :java_ok
set "JAVA_HOME="
for %%P in (
  "%ProgramFiles%\Android\Android Studio\jbr"
  "%ProgramFiles%\Android\Android Studio\jre"
  "%LOCALAPPDATA%\Programs\Android Studio\jbr"
) do (
  if not defined JAVA_HOME if exist "%%~P\bin\java.exe" set "JAVA_HOME=%%~P"
)
if defined JAVA_HOME goto :java_ok
where java >nul 2>nul
if not errorlevel 1 (
  echo  [!] JAVA_HOME не задан, использую java из PATH.
  goto :java_checked
)
echo  [ОШИБКА] Не найдена Java.
echo      Установите Android Studio: https://developer.android.com/studio
echo      или командой:  winget install -e --id Google.AndroidStudio
echo      Затем один раз откройте Android Studio, чтобы она скачала Android SDK.
goto :fail

:java_ok
echo  [OK] Java: %JAVA_HOME%
:java_checked

rem ---------- 2. Android SDK ----------
if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%\platforms" (
  echo  [ОШИБКА] Не найден Android SDK в "%ANDROID_HOME%".
  echo      Откройте Android Studio -^> More Actions -^> SDK Manager и установите
  echo      Android 14 ^(API 34^). Если SDK в другой папке - задайте ANDROID_HOME.
  goto :fail
)
echo  [OK] Android SDK: %ANDROID_HOME%

rem Gradle читает путь к SDK из local.properties (обратные слеши надо удвоить).
set "SDKDIR=%ANDROID_HOME:\=\\%"
> local.properties echo sdk.dir=%SDKDIR%

rem ---------- 3. Сборка ----------
echo.
echo  [..] Сборка. Первый раз скачивается Gradle и библиотеки (~500 МБ), 5-15 минут.
call gradlew.bat --no-daemon assembleDebug
if errorlevel 1 (
  echo.
  echo  [ОШИБКА] Сборка не удалась. Пролистайте вывод выше до первой строки с "error"
  echo           или "FAILURE" и пришлите её - разберёмся.
  goto :fail
)

if not exist "dist" mkdir "dist"
copy /y "app\build\outputs\apk\debug\app-debug.apk" "dist\peremena.apk" >nul
echo.
echo  ==== ГОТОВО ====
echo  APK: %CD%\dist\peremena.apk
echo  Перекиньте его на телефон и установите (см. README.md, раздел «Установка на телефон»).
echo.
start "" explorer "%CD%\dist"
pause
exit /b 0

:fail
echo.
pause
exit /b 1
