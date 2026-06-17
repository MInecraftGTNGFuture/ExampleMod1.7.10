#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$gradlew = Join-Path $projectRoot "gradlew.bat"

if (-not (Test-Path $gradlew)) {
    throw "gradlew.bat not found at $gradlew"
}

$gradleArgs = @("initProject", "--no-configuration-cache")

foreach ($arg in $args) {
    $gradleArgs += $arg
}

Push-Location $projectRoot
try {
    & $gradlew @gradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
