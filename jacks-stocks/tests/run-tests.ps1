param(
    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Users\Dell\.jdks\ms-17.0.15' })
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
$core = Join-Path $project 'app\src\main\java\com\jacks\stocks\core\Portfolio.java'
$tests = Join-Path $PSScriptRoot 'PortfolioTests.java'
if (!(Test-Path -LiteralPath $javac) -or !(Test-Path -LiteralPath $java)) {
    throw "Java tools not found in $JavaHome. Pass -JavaHome with an installed JDK."
}

$classes = Join-Path ([System.IO.Path]::GetTempPath()) ('jacks-stocks-core-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $classes | Out-Null
try {
    & $javac --release 8 -encoding UTF-8 '-Xlint:all,-options' -Werror -d $classes $core $tests
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed with exit code $LASTEXITCODE." }
    & $java -ea -cp $classes PortfolioTests
    if ($LASTEXITCODE -ne 0) { throw "Regression tests failed with exit code $LASTEXITCODE." }
} finally {
    Remove-Item -LiteralPath $classes -Recurse -Force
}