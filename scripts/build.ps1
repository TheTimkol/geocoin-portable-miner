$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# Prefer the developer's installed toolchain; use the Codex workspace copy when present.
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    $localTools = Join-Path $projectRoot '..\..\work\toolchain'
    $jdkRoot = Join-Path $localTools 'jdk-extract'
    $jdk = Get-ChildItem $jdkRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk -and (Test-Path (Join-Path $jdk.FullName 'bin\java.exe'))) {
        $javaHome = $jdk.FullName
        $env:JAVA_HOME = $javaHome
        $env:PATH = "$javaHome\bin;$env:PATH"
    }
}

$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue }
if ($mavenCommand) {
    $maven = $mavenCommand.Source
} else {
    $mavenRoot = Join-Path $projectRoot '..\..\work\toolchain\maven-extract'
    $mavenDir = Get-ChildItem $mavenRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $mavenDir) { throw 'Maven was not found. Install Maven 3.9 or newer and add it to PATH.' }
    $maven = Join-Path $mavenDir.FullName 'bin\mvn.cmd'
}

if (-not $javaHome -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java was not found. Install JDK 21 or newer and set JAVA_HOME or add Java to PATH.'
}

& $maven -f (Join-Path $projectRoot 'pom.xml') clean package -DskipTests
exit $LASTEXITCODE
