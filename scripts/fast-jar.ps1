# Fast incremental jar build — keeps the Gradle daemon, never cleans by default.
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

if (-not $env:JAVA_HOME) {
    $candidate = "C:\Users\Admin\.jdks\jdk-21.0.2"
    if (Test-Path $candidate) {
        $env:JAVA_HOME = $candidate
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    }
}

Write-Host "Building theseus jar (incremental, no clean)..."
& .\gradlew.bat jar --init-script init.gradle
if ($LASTEXITCODE -ne 0) {
    Write-Host "BUILD FAILED (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}

$jars = Get-ChildItem -Path (Join-Path $root "build\libs") -Filter "theseus-*.jar" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending
if ($jars) {
    Write-Host ("OK {0}" -f $jars[0].FullName)
} else {
    Write-Host "Build succeeded but no theseus-*.jar found under build\libs"
    exit 1
}
