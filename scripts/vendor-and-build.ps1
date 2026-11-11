$ErrorActionPreference = "Continue"
$proxy = "http://127.0.0.1:7897"
$root = Join-Path $PSScriptRoot "..\local-maven" | Resolve-Path
$env:JAVA_HOME = "C:\Users\Admin\.jdks\jdk-21.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location (Join-Path $PSScriptRoot "..")

function Test-ValidArtifact([string]$path, [string]$fileName) {
    if (-not (Test-Path $path)) { return $false }
    $len = (Get-Item $path).Length
    if ($len -lt 100) { return $false }
    $bytes = Get-Content $path -Encoding Byte -TotalCount 64
    $head = [System.Text.Encoding]::ASCII.GetString($bytes)
    if ($head -match '<html|<HTML|BlobNotFound|Error><Code>') { return $false }
    if ($fileName -match '\.(jar|zip)$') {
        return $bytes[0] -eq 0x50 -and $bytes[1] -eq 0x4B
    }
    if ($fileName -match '\.pom$') {
        return $head -match '<project' -and $len -gt 200
    }
    if ($fileName -match '\.module$') {
        return $head -match '^\s*\{' -and $len -gt 200
    }
    return $true
}

function Ensure-File([string]$group, [string]$artifact, [string]$version, [string]$fileName) {
    $version = $version.TrimEnd('.')
    $gpath = $group -replace '\.', '/'
    $dir = Join-Path $root "$gpath\$artifact\$version"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $out = Join-Path $dir $fileName
    if (Test-ValidArtifact $out $fileName) { return $true }

    $urls = New-Object System.Collections.Generic.List[string]
    if ($group -like 'com.mojang*' -or $group -like 'net.minecraft*') {
        $urls.Add("https://libraries.minecraft.net/$gpath/$artifact/$version/$fileName")
    }
    $urls.Add("https://maven.neoforged.net/releases/$gpath/$artifact/$version/$fileName")
    $urls.Add("https://maven.neoforged.net/mojang-meta/$gpath/$artifact/$version/$fileName")
    $urls.Add("https://maven.minecraftforge.net/$gpath/$artifact/$version/$fileName")
    $urls.Add("https://repo1.maven.org/maven2/$gpath/$artifact/$version/$fileName")
    if ($group -notlike 'com.mojang*') {
        $urls.Add("https://libraries.minecraft.net/$gpath/$artifact/$version/$fileName")
    }

    foreach ($url in $urls) {
        curl.exe -sL -x $proxy --connect-timeout 15 --max-time 300 -o $out $url 2>$null
        if (Test-ValidArtifact $out $fileName) {
            Write-Host ("OK {0} ({1})" -f $fileName, (Get-Item $out).Length)
            return $true
        }
        Remove-Item $out -Force -ErrorAction SilentlyContinue
    }
    Write-Host "FAIL $fileName"
    return $false
}

function Ensure-Coord([string]$g, [string]$a, [string]$v) {
    $v = $v.TrimEnd('.')
    Write-Host ("Ensure {0}:{1}:{2}" -f $g, $a, $v)
    $files = @(
        "$a-$v.pom", "$a-$v.module", "$a-$v.jar", "$a-$v.zip",
        "$a-$v-all.jar", "$a-$v-fatjar.jar", "$a-$v-api.jar",
        "$a-$v-userdev.jar", "$a-$v-sources.jar", "$a-$v-universal.jar", "$a-$v-joined.jar",
        "$a-$v-moddev-config.json"
    )
    foreach ($f in $files) { Ensure-File $g $a $v $f | Out-Null }
}

# cleanup invalid tiny artifacts
Get-ChildItem $root -Recurse -File | ForEach-Object {
    if (-not (Test-ValidArtifact $_.FullName $_.Name)) {
        if ($_.Length -lt 2000) {
            Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
        }
    }
}

for ($i = 1; $i -le 200; $i++) {
    Write-Host "===== ATTEMPT $i ====="
    $log = "gradle-fix10-$i.log"
    & .\gradlew.bat compileJava --no-daemon --init-script init.gradle *> $log
    if ($LASTEXITCODE -eq 0) {
        Write-Host "SUCCESS"
        Get-Content $log -Tail 60
        exit 0
    }
    $text = Get-Content $log -Raw
    $coords = New-Object System.Collections.Generic.HashSet[string]
    foreach ($m in [regex]::Matches($text, 'Could not (?:find|download) ([^\s\(]+) \(([^:]+):([^:]+):([^)]+)\)')) {
        $file = $m.Groups[1].Value
        $g = $m.Groups[2].Value
        $a = $m.Groups[3].Value
        $v = $m.Groups[4].Value.TrimEnd('.')
        Write-Host ("NeedFile {0}" -f $file)
        Ensure-File $g $a $v $file | Out-Null
        [void]$coords.Add(("{0}:{1}:{2}" -f $g, $a, $v))
    }
    foreach ($m in [regex]::Matches($text, 'Could not (?:find|resolve) ([a-zA-Z][\w.-]*:[\w.-]*:[0-9][\w.+-]*)')) {
        [void]$coords.Add($m.Groups[1].Value.TrimEnd('.'))
    }
    if ($coords.Count -eq 0) {
        Write-Host "UNPARSED"
        Select-String -Path $log -Pattern "Could not|error:|BUILD SUCCESS|Compilation" | Select-Object -First 80
        exit 1
    }
    foreach ($c in $coords) {
        if ($c -match '^([^:]+):([^:]+):(.+)$') { Ensure-Coord $Matches[1] $Matches[2] $Matches[3] }
    }
}
exit 1
