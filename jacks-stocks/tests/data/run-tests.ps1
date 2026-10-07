param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin\javac.exe' } else { (Get-Command javac -ErrorAction Stop).Source }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$classes = Join-Path ([System.IO.Path]::GetTempPath()) ('jacks-backup-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes | Out-Null
try {
    $sources = @(
        (Join-Path $root 'app\src\main\java\com\jacks\stocks\core\Portfolio.java'),
        (Join-Path $root 'app\src\main\java\com\jacks\stocks\data\Backup.java'),
        (Join-Path $root 'tests\data\BackupTest.java')
    )
    & $javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d $classes $sources
    if ($LASTEXITCODE -ne 0) { throw "Backup compilation failed: $LASTEXITCODE" }
    foreach ($zone in @('UTC', 'Pacific/Honolulu')) {
        Write-Output "Running backup regressions with host timezone $zone"
        & $java "-Duser.timezone=$zone" -cp $classes com.jacks.stocks.data.BackupTest
        if ($LASTEXITCODE -ne 0) { throw "Backup regression failed: $LASTEXITCODE" }
    }
} finally {
    if (Test-Path $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
}