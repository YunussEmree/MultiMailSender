# Prints the home of the newest installed JDK >= 17 (one with javac), or nothing.
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$cmd = Get-Command java -ErrorAction SilentlyContinue
if ($cmd) { $candidates += (Split-Path (Split-Path $cmd.Source)) }
$roots = 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft', 'C:\Program Files\Zulu', 'C:\Program Files\Amazon Corretto'
foreach ($r in $roots) {
    if (Test-Path $r) { $candidates += (Get-ChildItem $r -Directory | ForEach-Object FullName) }
}
$best = $null; $bestVer = 0
foreach ($h in ($candidates | Select-Object -Unique)) {
    $java = Join-Path $h 'bin\java.exe'
    if (-not (Test-Path $java) -or -not (Test-Path (Join-Path $h 'bin\javac.exe'))) { continue }
    $out = & cmd /c "`"$java`" -version 2>&1" | Out-String
    if ($out -match 'version "(\d+)') {
        $v = [int]$Matches[1]
        if ($v -ge 17 -and $v -gt $bestVer) { $best = $h; $bestVer = $v }
    }
}
if ($best) { Write-Output $best }
