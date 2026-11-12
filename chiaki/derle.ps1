param([string[]]$Abi = @('armeabi-v7a','arm64-v8a'))
# Builds libturkce_chiaki.so for armeabi-v7a and copies it next to the app's native libraries.
# Sources: chiaki-ng v1.10.0 and Mbed TLS v2.28.10 under %LOCALAPPDATA%\TurkceTV\chiaki.
$ErrorActionPreference = 'Stop'
$tools = "$env:LOCALAPPDATA\TurkceTV\motor\araclar"
$bin = "$tools\cmake\bin"
$base = "$env:LOCALAPPDATA\TurkceTV\chiaki"
$env:Path = "$bin;$env:Path"

foreach ($targetAbi in $Abi) {
    if ($targetAbi -notin @('armeabi-v7a', 'arm64-v8a')) { throw "Unsupported ABI: $targetAbi" }
    $build = if ($targetAbi -eq 'armeabi-v7a') { "$env:LOCALAPPDATA\TurkceTV\chiaki\derleme" } else { "$env:LOCALAPPDATA\TurkceTV\chiaki\derleme-$targetAbi" }
& "$bin\cmake.exe" -S $PSScriptRoot -B $build -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$bin\ninja.exe" `
    "-DCMAKE_TOOLCHAIN_FILE=$tools\android-ndk-r28\build\cmake\android.toolchain.cmake" `
    "-DANDROID_ABI=$targetAbi" -DANDROID_PLATFORM=android-24 `
    -DCMAKE_BUILD_TYPE=Release "-DCHIAKI=$base\chiaki-ng" "-DMBEDTLS=$base\mbedtls"
if ($LASTEXITCODE -ne 0) { throw 'configure failed' }

& "$bin\ninja.exe" -C $build -k 0 turkce_chiaki
if ($LASTEXITCODE -ne 0) { throw 'build failed' }

$out = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\$targetAbi"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item "$build\libturkce_chiaki.so" $out -Force
Get-Item "$out\libturkce_chiaki.so" | Select-Object Name, Length

}
