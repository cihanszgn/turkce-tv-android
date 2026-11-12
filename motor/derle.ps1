param([string[]]$Abi = @('armeabi-v7a','arm64-v8a'))
# Builds libturkce_mt.so for armeabi-v7a and copies it next to the app's native libraries.
$ErrorActionPreference = 'Stop'
$tools = "$env:LOCALAPPDATA\TurkceTV\motor\araclar"
$ndk = "$tools\android-ndk-r28"
$bin = "$tools\cmake\bin"
$source = "$env:LOCALAPPDATA\TurkceTV\motor\bergamot-sys\bergamot-translator"

# PCRE2 is a nested CMake run that gets no -G flag; on Windows it would pick Visual Studio.
$env:CMAKE_GENERATOR = 'Ninja'
$env:Path = "$bin;$env:Path"

# Stock Marian reads unaligned values, which crash with SIGBUS on 32-bit ARM.
$marian = "$source\3rd_party\marian-dev"
$patch = "$PSScriptRoot\marian-32bit.patch"
git -C $marian apply --reverse --check $patch
if ($LASTEXITCODE -ne 0) {
    git -C $marian apply $patch
    if ($LASTEXITCODE -ne 0) { throw 'patch failed' }
}

foreach ($targetAbi in $Abi) {
    if ($targetAbi -notin @('armeabi-v7a', 'arm64-v8a')) { throw "Unsupported ABI: $targetAbi" }
    $build = if ($targetAbi -eq 'armeabi-v7a') { "$env:LOCALAPPDATA\TurkceTV\motor\derleme" } else { "$env:LOCALAPPDATA\TurkceTV\motor\derleme-$targetAbi" }
& "$bin\cmake.exe" -S $PSScriptRoot -B $build -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$bin\ninja.exe" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
    "-DANDROID_ABI=$targetAbi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static `
    -DCMAKE_BUILD_TYPE=Release "-DBERGAMOT_DIR=$source"
if ($LASTEXITCODE -ne 0) { throw 'configure failed' }

& "$bin\ninja.exe" -C $build -k 0 turkce_mt
if ($LASTEXITCODE -ne 0) { throw 'build failed' }

$out = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\$targetAbi"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item "$build\libturkce_mt.so" $out -Force
Get-Item "$out\libturkce_mt.so" | Select-Object Name, Length

}
