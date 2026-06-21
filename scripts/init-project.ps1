#Requires -Version 5.1
$ErrorActionPreference = "Stop"

function Read-InitValue {
    param(
        [string]$Label,
        [string]$Default = ""
    )

    if ($Default) {
        $answer = Read-Host "$Label [$Default]"
        if ([string]::IsNullOrWhiteSpace($answer)) {
            return $Default
        }
        return $answer.Trim()
    }

    return (Read-Host $Label).Trim()
}

function ConvertTo-ModId {
    param([string]$ModName)
    return ($ModName.ToLower() -replace '[^a-z0-9]+', '')
}

function ConvertTo-MainClassName {
    param([string]$ModName)
    $parts = $ModName -split '[^a-zA-Z0-9]+' | Where-Object { $_ -ne '' }
    if ($parts.Count -eq 0) {
        return "MyMod"
    }
    return ($parts | ForEach-Object {
        $_.Substring(0, 1).ToUpper() + $_.Substring(1).ToLower()
    }) -join ''
}

function ConvertTo-DefaultModGroup {
    param(
        [string]$Author,
        [string]$ModId
    )

    $authorPart = (($Author -split '[^a-zA-Z0-9]+' | Where-Object { $_ -ne '' }) -join '').ToLower()
    if ([string]::IsNullOrWhiteSpace($authorPart)) {
        $authorPart = "example"
    }
    return "com.$authorPart.$ModId"
}

function Test-InitPropertiesProvided {
    param([string[]]$Arguments)

    foreach ($argument in $Arguments) {
        if ($argument -match '^-Pinit\.') {
            return $true
        }
    }
    return $false
}

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$gradlew = Join-Path $projectRoot "gradlew.bat"

if (-not (Test-Path $gradlew)) {
    throw "gradlew.bat not found at $gradlew"
}

$gradleArgs = @(
    "initProject",
    "--no-configuration-cache",
    "--no-daemon"
)

if (-not (Test-InitPropertiesProvided -Arguments $args)) {
    Write-Host ""
    Write-Host "Configure your mod from the template."
    Write-Host ""

    $modName = Read-InitValue "Mod name (human-readable)" "MyMod"
    $defaultModId = ConvertTo-ModId $modName
    if ([string]::IsNullOrWhiteSpace($defaultModId)) {
        $defaultModId = "mymodid"
    }
    $modId = Read-InitValue "Mod ID (lowercase, e.g. mymod)" $defaultModId
    $author = Read-InitValue "Author name" "Developer"
    $defaultModGroup = ConvertTo-DefaultModGroup $author $modId
    $modGroup = Read-InitValue "Root package (modGroup)" $defaultModGroup
    $mainClass = Read-InitValue "Main @Mod class name" (ConvertTo-MainClassName $modName)
    $description = Read-InitValue "Short mod description" "A Minecraft 1.7.10 Forge mod."
    $url = Read-InitValue "Project URL (optional)" ""
    $licenseAnswer = Read-InitValue "Create LICENSE from LICENSE-template? (y/N)" "n"
    $writeLicense = $licenseAnswer -match '^(y|yes)$'
    $serverOnlyAnswer = Read-InitValue "Server-only mod (clients without the mod can connect)? (y/N)" "n"
    $serverOnlyMod = $serverOnlyAnswer -match '^(y|yes)$'

    $gradleArgs += "-Pinit.modName=$modName"
    $gradleArgs += "-Pinit.modId=$modId"
    $gradleArgs += "-Pinit.author=$author"
    $gradleArgs += "-Pinit.modGroup=$modGroup"
    $gradleArgs += "-Pinit.mainClass=$mainClass"
    $gradleArgs += "-Pinit.description=$description"
    $gradleArgs += "-Pinit.url=$url"
    $gradleArgs += "-Pinit.license=$writeLicense"
    $gradleArgs += "-Pinit.serverOnly=$serverOnlyMod"

    if ($writeLicense) {
        $copyright = Read-InitValue "Copyright holder for LICENSE" $author
        $gradleArgs += "-Pinit.copyright=$copyright"
    }
}

foreach ($argument in $args) {
    $gradleArgs += $argument
}

Push-Location $projectRoot
try {
    & $gradlew @gradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
