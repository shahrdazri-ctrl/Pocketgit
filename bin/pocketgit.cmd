@echo off
setlocal
set "POCKETGIT_JAR=%~dp0..\target\pocketgit.jar"
if not exist "%POCKETGIT_JAR%" (
  echo error: build PocketGit first with mvn clean verify 1>&2
  exit /b 1
)
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -jar "%POCKETGIT_JAR%" %*
) else (
  java -jar "%POCKETGIT_JAR%" %*
)
exit /b %errorlevel%
