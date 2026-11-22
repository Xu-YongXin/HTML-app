# 纯命令行构建 APK（不需要 Android Studio / Gradle）。
#
# 依赖：JDK 17（javac、keytool）+ Android SDK 的 build-tools 与 platform。
# 默认从 tools\jdk 与 tools\sdk 读取；也可以用 -JavaHome / -AndroidHome 指定，
# 或提前设置好 JAVA_HOME / ANDROID_HOME 环境变量。
#
#   powershell -ExecutionPolicy Bypass -File tools\build-local.ps1
#
# 产物：dist\app-debug.apk

param(
    [string]$JavaHome,
    [string]$AndroidHome,
    [string]$OutDir = "dist",
    [string]$BuildToolsVersion = "34.0.0",
    [string]$Platform = "android-34",
    [int]$MinSdk = 24,
    [int]$TargetSdk = 34,
    [string]$VersionName = "1.0.0",
    [int]$VersionCode = 1
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# ---------- 定位工具链 ----------
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $candidate = Get-ChildItem -Path (Join-Path $Root "tools\jdk") -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } | Select-Object -First 1
    if ($candidate) { $JavaHome = $candidate.FullName }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome "bin\javac.exe"))) {
    Fail "找不到 JDK（需要 javac）。请设置 -JavaHome 或 JAVA_HOME。"
}

if (-not $AndroidHome) { $AndroidHome = $env:ANDROID_HOME }
if (-not $AndroidHome -and (Test-Path (Join-Path $Root "tools\sdk\build-tools"))) {
    $AndroidHome = Join-Path $Root "tools\sdk"
}
if (-not $AndroidHome) { Fail "找不到 Android SDK（需要 build-tools 与 platform）。请设置 -AndroidHome 或 ANDROID_HOME。" }

$BuildTools = Join-Path $AndroidHome "build-tools\$BuildToolsVersion"
$AndroidJar = Join-Path $AndroidHome "platforms\$Platform\android.jar"
foreach ($p in @(
        (Join-Path $BuildTools "aapt2.exe"),
        (Join-Path $BuildTools "zipalign.exe"),
        (Join-Path $BuildTools "apksigner.bat"),
        $AndroidJar)) {
    if (-not (Test-Path $p)) { Fail "缺少文件：$p" }
}

# d8 / apksigner 是 Java 程序，需要 java 在 PATH 里
$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome "bin") + ";" + $BuildTools + ";" + $env:PATH

$AppDir = Join-Path $Root "app\src\main"
$BuildDir = Join-Path $Root "build-local"
$OutPath = Join-Path $Root $OutDir
New-Item -ItemType Directory -Force -Path $OutPath | Out-Null

if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
foreach ($d in @("res", "gen", "classes", "dex")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $BuildDir $d) | Out-Null
}

Write-Host "==> 1/6 编译资源 (aapt2 compile)" -ForegroundColor Cyan
& (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $AppDir "res") -o (Join-Path $BuildDir "res.zip")
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile 失败" }

Write-Host "==> 2/6 链接资源与清单 (aapt2 link)" -ForegroundColor Cyan
# Gradle 会把 namespace / applicationId 注入清单，手工构建需要先补一个临时清单。
$PackageName = "com.dsh.htmlviewer"
$Manifest = Join-Path $BuildDir "AndroidManifest.local.xml"
$xml = [IO.File]::ReadAllText((Join-Path $AppDir "AndroidManifest.xml"))
$xml = $xml.Replace('${applicationId}', $PackageName)
if ($xml -notmatch '<manifest[^>]*\spackage=') {
    $xml = $xml -replace '<manifest ', "<manifest package=""$PackageName"" "
}
[IO.File]::WriteAllText($Manifest, $xml, [Text.UTF8Encoding]::new($false))

& (Join-Path $BuildTools "aapt2.exe") link `
    -o (Join-Path $BuildDir "base.apk") `
    -I $AndroidJar `
    --manifest $Manifest `
    -R (Join-Path $BuildDir "res.zip") `
    -A (Join-Path $AppDir "assets") `
    --java (Join-Path $BuildDir "gen") `
    --min-sdk-version $MinSdk `
    --target-sdk-version $TargetSdk `
    --version-code $VersionCode `
    --version-name $VersionName `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link 失败" }

Write-Host "==> 3/6 编译 Java (javac)" -ForegroundColor Cyan
$Sources = @(Get-ChildItem -Path (Join-Path $AppDir "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$Sources += @(Get-ChildItem -Path (Join-Path $BuildDir "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$SourceFile = Join-Path $BuildDir "sources.txt"
[IO.File]::WriteAllLines($SourceFile, $Sources, [Text.UTF8Encoding]::new($false))

& (Join-Path $JavaHome "bin\javac.exe") `
    -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options `
    -bootclasspath $AndroidJar `
    -classpath (Join-Path $BuildTools "core-lambda-stubs.jar") `
    -d (Join-Path $BuildDir "classes") `
    "@$SourceFile"
if ($LASTEXITCODE -ne 0) { Fail "javac 失败" }

Write-Host "==> 4/6 转 dex (d8)" -ForegroundColor Cyan
$Classes = @(Get-ChildItem -Path (Join-Path $BuildDir "classes") -Recurse -Filter *.class |
        ForEach-Object { $_.FullName })
& (Join-Path $BuildTools "d8.bat") --release --min-api $MinSdk --lib $AndroidJar `
    --output (Join-Path $BuildDir "dex") @Classes
if ($LASTEXITCODE -ne 0) { Fail "d8 失败" }

Write-Host "==> 5/6 打包并对齐 (zip + zipalign)" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = Join-Path $BuildDir "base.apk"
$zip = [IO.Compression.ZipFile]::Open($apk, [IO.Compression.ZipArchiveMode]::Update)
$dexPath = Join-Path $BuildDir "dex\classes.dex"
try {
    $existing = $zip.GetEntry("classes.dex")
    if ($existing) { $existing.Delete() }
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $dexPath, "classes.dex", [IO.Compression.CompressionLevel]::Optimal) | Out-Null

    # Windows 版 aapt2 会把 assets 路径写成反斜杠（assets\www\index.html），
    # Android 只认正斜杠，这里统一修正，否则 WebView 加载不到页面。
    foreach ($entry in @($zip.Entries)) {
        if ($entry.FullName.Contains('\')) {
            $fixed = $entry.FullName.Replace('\', '/')
            $newEntry = $zip.CreateEntry($fixed, [IO.Compression.CompressionLevel]::Optimal)
            $src = $entry.Open()
            $dst = $newEntry.Open()
            try { $src.CopyTo($dst) } finally { $dst.Dispose(); $src.Dispose() }
            $entry.Delete()
        }
    }
} finally {
    $zip.Dispose()
}
& (Join-Path $BuildTools "zipalign.exe") -p -f 4 $apk (Join-Path $BuildDir "aligned.apk")
if ($LASTEXITCODE -ne 0) { Fail "zipalign 失败" }

Write-Host "==> 6/6 签名 (apksigner)" -ForegroundColor Cyan
$Keystore = Join-Path $Root "build-local\debug.keystore"
if (-not (Test-Path $Keystore)) {
    & (Join-Path $JavaHome "bin\keytool.exe") -genkeypair -v `
        -keystore $Keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "生成调试签名失败" }
}
$FinalApk = Join-Path $OutPath "app-debug.apk"
& (Join-Path $BuildTools "apksigner.bat") sign `
    --ks $Keystore --ks-pass pass:android --key-pass pass:android `
    --out $FinalApk (Join-Path $BuildDir "aligned.apk")
if ($LASTEXITCODE -ne 0) { Fail "apksigner 签名失败" }

& (Join-Path $BuildTools "apksigner.bat") verify --print-certs $FinalApk | Select-Object -First 3

$size = [math]::Round((Get-Item $FinalApk).Length / 1KB, 1)
Write-Host ""
Write-Host "构建完成：$FinalApk ($size KB)" -ForegroundColor Green
exit 0
