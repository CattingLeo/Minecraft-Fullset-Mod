@echo off
cd /d "%~dp0"
echo ============================================
echo   Building the Fullset mod...
echo   This can take a minute or two. Please wait.
echo ============================================
echo.
call gradlew.bat clean build
echo.
echo ============================================
echo  If you see BUILD SUCCESSFUL above, your mod
echo  is ready at:  build\libs\fullset-1.1.0.jar
echo.
echo  If you see BUILD FAILED, copy the red text
echo  and send it to get help.
echo ============================================
echo.
pause
