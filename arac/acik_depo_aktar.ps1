# Copies the TV app's source into a clean folder for the public repository (AGPL source release).
# Leaves out history, internal notes, measurement logs and the iPhone code. Run again for every release.
# Usage: .\acik_depo_aktar.ps1 -Hedef C:\Cihan.Ai\02-Uygulama\turkce-tv-android [-Destek adres]
param(
    [Parameter(Mandatory = $true)][string]$Hedef,
    [string]$Destek = 'https://github.com/cihanszgn/turkce-tv-android/issues'
)
$ErrorActionPreference = 'Stop'
$olcum = Split-Path $PSScriptRoot -Parent
$depo = Split-Path (Split-Path $olcum -Parent) -Parent
$utf8 = New-Object System.Text.UTF8Encoding($false)

New-Item -ItemType Directory -Force $Hedef | Out-Null
# Everything but .git is replaced, so files removed from the app also disappear from the release.
Get-ChildItem $Hedef -Force | Where-Object { $_.Name -ne '.git' } | Remove-Item -Recurse -Force

function Kopyala($kaynak, $hedef, $haric) {
    robocopy $kaynak $hedef /E /XD $haric /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "copy failed: $kaynak" }
}
Kopyala "$olcum\app" "$Hedef\app" @('build', 'jniLibs')
Kopyala "$olcum\chiaki" "$Hedef\chiaki" @()
Kopyala "$olcum\motor" "$Hedef\motor" @()
Kopyala "$depo\tv\gradle" "$Hedef\gradle" @()
New-Item -ItemType Directory -Force "$Hedef\arac", "$Hedef\chiaki\core\src", "$Hedef\docs" | Out-Null
Copy-Item "$olcum\arac\demo_klip_uret.py", "$olcum\arac\gorsel_uret.py", "$olcum\arac\acik_depo_aktar.ps1" "$Hedef\arac"
Copy-Item "$depo\tv\gradlew", "$depo\tv\gradlew.bat" $Hedef
Copy-Item "$olcum\build.gradle.kts", "$olcum\settings.gradle.kts", "$olcum\gradle.properties" $Hedef

# The two chiaki files that live next to the iPhone code in the private repository.
Copy-Item "$depo\ios\ChiakiCore\src\holepunch_stub.c" "$Hedef\chiaki\core\src"
Copy-Item "$depo\ios\ChiakiCore\generated" "$Hedef\chiaki\core\generated" -Recurse
Copy-Item "$depo\ios\ChiakiCore\LICENSE-OpenSSL-exception" "$Hedef\chiaki\core"
$cmake = "$Hedef\chiaki\CMakeLists.txt"
$eski = 'set(CORE "${CMAKE_CURRENT_SOURCE_DIR}/../../../ios/ChiakiCore")'
$metin = [System.IO.File]::ReadAllText($cmake)
if (-not $metin.Contains($eski)) { throw 'CORE line not found in chiaki/CMakeLists.txt' }
[System.IO.File]::WriteAllText($cmake, $metin.Replace($eski, 'set(CORE "${CMAKE_CURRENT_SOURCE_DIR}/core")'), $utf8)

Copy-Item "$olcum\app\src\main\assets\lisanslar\AGPL-3.0.txt" "$Hedef\LICENSE"
Copy-Item "$olcum\app\src\main\assets\lisanslar\UCUNCU-TARAF.txt" "$Hedef\UCUNCU-TARAF.txt"
Copy-Item "$olcum\arac\acik-depo\README.md" "$Hedef\README.md"
[System.IO.File]::WriteAllText("$Hedef\.gitignore", ".gradle/`n.kotlin/`nbuild/`napp/build/`nlocal.properties`napp/src/main/jniLibs/`ndist/`n", $utf8)

# The privacy page and the landing page, served by GitHub Pages from /docs.
foreach ($sayfa in 'index.html', 'gizlilik.html') {
    $html = [System.IO.File]::ReadAllText("$depo\docs\yayin\play\site\$sayfa").Replace('[DESTEK_EPOSTA]', $Destek)
    [System.IO.File]::WriteAllText("$Hedef\docs\$sayfa", $html, $utf8)
}

# Nothing private may leave: home network addresses, mail addresses, user folders, key files.
$metinler = Get-ChildItem $Hedef -Recurse -File | Where-Object { $_.FullName -notmatch '\\\.git\\' -and $_.Extension -notin '.bin', '.spm', '.mp4', '.png', '.jar' }
# The real home addresses (from the private measurement notes) and the private mail address must appear nowhere.
$gercek = @(Select-String -Path "$olcum\TEST-PLANI.md", "$olcum\SONUC.md" -Pattern '\b(?:192\.168|10\.\d+)\.\d+\.\d+\b' -AllMatches | ForEach-Object { $_.Matches.Value } | Sort-Object -Unique)
$eposta = git -C $depo config user.email
$kesin = @($gercek) + @($eposta) | Where-Object { $_ } | ForEach-Object { [regex]::Escape($_) }
$supheli = @($metinler | Select-String -Pattern $kesin)
# Example addresses in the tests and contributor mails in the third-party licence texts are not ours.
$supheli += @($metinler | Where-Object { $_.FullName -notmatch '\\lisanslar\\|\\src\\test\\|UCUNCU-TARAF' } |
    Select-String -Pattern '192\.168\.\d+\.\d+', '@gmail\.', 'Users\\cihan', 'storePassword\s*=\s*\S', 'BEGIN (RSA |EC )?PRIVATE KEY' |
    Where-Object { $_.Line -notmatch 'getProperty\("storePassword"\)' })
if ($supheli) { $supheli | ForEach-Object { "{0}:{1}" -f $_.Path, $_.LineNumber }; throw 'private data found, nothing was published' }
if (Get-ChildItem $Hedef -Recurse -File -Include *.jks, *.keystore, *.p8, *.properties | Where-Object { $_.Name -notin 'gradle.properties', 'gradle-wrapper.properties' }) { throw 'key or properties file in the release' }

$dosya = (Get-ChildItem $Hedef -Recurse -File | Where-Object { $_.FullName -notmatch '\\\.git\\' })
"Tamam: {0} dosya, {1:N1} MB -> {2}" -f $dosya.Count, (($dosya | Measure-Object Length -Sum).Sum / 1MB), $Hedef
