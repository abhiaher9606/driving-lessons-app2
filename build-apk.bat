@echo off
setlocal
cd /d "%~dp0"
echo ============================================
echo        DriveLog APK Builder
 echo ============================================
where gradle >nul 2>&1
if %errorlevel%==0 (
  echo Gradle found. Building APK...
  gradle assembleDebug
  if %errorlevel%==0 goto DONE
  echo Gradle build failed.
  goto END
)
echo.
echo Gradle was not found on this PC.
echo.
echo FASTEST METHOD:
echo 1. Install Android Studio.
echo 2. Open this DriveLog folder in Android Studio.
echo 3. Let Android Studio finish Gradle sync and install any SDK components it requests.
echo 4. In Android Studio, use Build ^> Make Project once.
echo 5. After Gradle is configured, run this BAT again.
echo.
echo The APK will be: app\build\outputs\apk\debug\app-debug.apk
pause
goto END
:DONE
echo.
echo BUILD SUCCESSFUL!
echo APK: %cd%\app\build\outputs\apk\debug\app-debug.apk
start "" "%cd%\app\build\outputs\apk\debug"
:END
endlocal
