$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Gradle 8.10.2 cannot run on Java 25. This Minecraft/NeoForge project uses JDK 21.
# JAVA21_HOME can be set for this project without changing the system-wide Java.
function Test-Jdk21([string]$HomePath) {
    if ([string]::IsNullOrWhiteSpace($HomePath)) { return $false }
    $HomePath = $HomePath.Trim('"').TrimEnd('\', '/')
    $java = Join-Path $HomePath 'bin\java.exe'
    $javac = Join-Path $HomePath 'bin\javac.exe'
    if (!(Test-Path -LiteralPath $java -PathType Leaf) -or
        !(Test-Path -LiteralPath $javac -PathType Leaf)) { return $false }
    # javac -version writes its version to stdout, unlike java -version.
    $versionText = (& $javac -version | Out-String).Trim()
    return ($LASTEXITCODE -eq 0 -and $versionText -match '^javac\s+21(?:\.|\s|$)')
}

Write-Host 'Searching for a JDK 21 installation...'
$candidates = New-Object 'System.Collections.Generic.List[string]'
foreach ($var in @('JAVA21_HOME', 'JDK21_HOME', 'JAVA_HOME')) {
    $value = [Environment]::GetEnvironmentVariable($var)
    if (-not [string]::IsNullOrWhiteSpace($value)) { $candidates.Add($value) }
}

# Also try the currently visible javac on PATH.
$javacCommand = Get-Command 'javac.exe' -ErrorAction SilentlyContinue
if ($javacCommand -and $javacCommand.Source) {
    $candidates.Add((Split-Path (Split-Path $javacCommand.Source -Parent) -Parent))
}

$searchRoots = @(
    (Join-Path $env:ProgramFiles 'Microsoft'),
    (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
    (Join-Path $env:ProgramFiles 'Java'),
    (Join-Path $env:ProgramFiles 'Amazon Corretto'),
    (Join-Path $env:ProgramFiles 'Zulu'),
    (Join-Path $env:USERPROFILE '.jdks'),
    (Join-Path $env:USERPROFILE '.jdks\jdks'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium')
)
foreach ($root in $searchRoots) {
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
    foreach ($folder in @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue)) {
        if ($folder.Name -match '(?i)(?:jdk-?|temurin-|msopenjdk-|corretto-|zulu).*?21(?:[.\-_+]|$)') {
            $candidates.Add($folder.FullName)
        }
    }
}

$jdk21 = $null
foreach ($candidate in @($candidates | Select-Object -Unique)) {
    if (Test-Jdk21 $candidate) {
        $jdk21 = $candidate.Trim('"').TrimEnd('\', '/')
        break
    }
}

if (-not $jdk21) {
    Write-Host ''
    Write-Host 'ERROR: A 64-bit JDK 21 installation was not found.' -ForegroundColor Red
    Write-Host 'This project uses Gradle 8.10.2, which cannot run on Java 25.'
    Write-Host 'Install JDK 21 from https://learn.microsoft.com/en-us/java/openjdk/download'
    Write-Host 'Alternatively, set JAVA21_HOME to your JDK 21 folder before building.'
    Write-Host 'Example (PowerShell; replace with your actual installed directory):'
    Write-Host '  $env:JAVA21_HOME = "C:\Program Files\Microsoft\jdk-21.0.x"'
    throw 'JDK 21 is required. Build has not started.'
}

# Override the environment only in this PowerShell process and the spawned Gradle process.
$env:JAVA_HOME = $jdk21
$env:PATH = "$(Join-Path $jdk21 'bin');$env:PATH"
Write-Host "Using JDK 21: $jdk21" -ForegroundColor Green
& (Join-Path $jdk21 'bin\java.exe') -version

$gradleVer = '8.10.2'
$localFolder = Join-Path $PSScriptRoot '.gradle-local'
$gradleBin = Join-Path $localFolder "gradle-$gradleVer/bin/gradle.bat"
if (-not (Test-Path $gradleBin)) {
    New-Item -ItemType Directory -Path $localFolder -Force | Out-Null
    $zip = Join-Path $localFolder "gradle-$gradleVer-bin.zip"
    $url = "https://services.gradle.org/distributions/gradle-$gradleVer-bin.zip"
    Write-Host "Downloading Gradle $gradleVer..."
    Invoke-WebRequest -Uri $url -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $localFolder -Force
    Remove-Item $zip
}
& $gradleBin build --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed; inspect the error above.' }
Write-Host "Build completed: $PSScriptRoot/build/libs/"
