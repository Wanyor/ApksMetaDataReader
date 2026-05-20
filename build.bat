@echo off
setlocal

set "ROOT=%~dp0"
set "SRC=%ROOT%src"
set "OUT=%ROOT%out\production\ApksMetaDataReader"
set "JAR_DIR=%ROOT%out\artifacts\ApksMetaDataReader_jar"
set "JAR=%JAR_DIR%\ApksMetaDataReader.jar"
set "MAIN=com.wanyor.android.app.MetaDataReader"
set "SRCS=%TEMP%\apk_sources.txt"
set "MF=%TEMP%\apk_manifest.mf"

echo [1/4] Clean output...
if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%JAR_DIR%" rmdir /s /q "%JAR_DIR%"
mkdir "%OUT%"
mkdir "%JAR_DIR%"

echo [2/4] Collect source files...
dir /s /b "%SRC%\*.java" > "%SRCS%"
if not exist "%SRCS%" (
    echo ERROR: no .java files found
    exit /b 1
)

echo [3/4] Compile...
javac -encoding UTF-8 -d "%OUT%" @"%SRCS%"
if errorlevel 1 (
    echo ERROR: compile failed
    del "%SRCS%"
    exit /b 1
)
del "%SRCS%"

echo [4/4] Package JAR...
echo Manifest-Version: 1.0> "%MF%"
echo Main-Class: %MAIN%>> "%MF%"
echo.>> "%MF%"

jar cfm "%JAR%" "%MF%" -C "%OUT%" .
if errorlevel 1 (
    echo ERROR: jar failed
    del "%MF%"
    exit /b 1
)
del "%MF%"

echo.
echo Done: %JAR%
echo Run:  java -jar "%JAR%" [apk-path]
endlocal
