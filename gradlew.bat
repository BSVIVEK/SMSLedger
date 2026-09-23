@echo off
setlocal
set "PROJECT_DIR=%~dp0"
set "JAVA_CMD=java.exe"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not exist "%PROJECT_DIR%gradle\wrapper\gradle-wrapper.jar" (
  "%JAVA_CMD%" "%PROJECT_DIR%scripts\GradleBootstrap.java" "%PROJECT_DIR%."
  if errorlevel 1 exit /b 1
)
"%JAVA_CMD%" -Dorg.gradle.appname=gradlew -classpath "%PROJECT_DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
