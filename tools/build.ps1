param([switch]$DebugBuild, [switch]$DeviceTests, [string]$DeviceSerial, [switch]$UseSessionProxy)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$portableRoot = Join-Path (Split-Path $projectRoot -Parent) '.toolchains\android'
if (-not $env:JAVA_HOME) {
    $portableJdk = Get-ChildItem -LiteralPath $portableRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($portableJdk) { $env:JAVA_HOME = $portableJdk.FullName }
}
if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath (Join-Path $portableRoot 'sdk'))) {
    $env:ANDROID_HOME = Join-Path $portableRoot 'sdk'
}
if (-not $env:GRADLE_USER_HOME -and (Test-Path -LiteralPath $portableRoot)) {
    $env:GRADLE_USER_HOME = Join-Path $portableRoot 'gradle-cache'
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw '请配置 JAVA_HOME，指向 JDK17。'
}
if (-not $env:ANDROID_HOME) { throw '请配置 ANDROID_HOME，指向 Android SDK。' }

$variant = if ($DebugBuild -or $DeviceTests) { 'debug' } else { 'release' }
if ($variant -eq 'release' -and -not (Test-Path -LiteralPath (Join-Path $projectRoot 'signing.properties'))) {
    throw '正式构建需要本机 signing.properties。首个发布版本可先运行 tools/init-signing.ps1；后续版本必须复用原签名。'
}
$tasks = if ($variant -eq 'release') {
    @(':app:assembleRelease', ':app:testReleaseUnitTest', ':app:lintRelease')
} else {
    @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:compileDebugAndroidTestJavaWithJavac', ':app:lintDebug')
}
if ($DeviceTests) { $tasks += ':app:assembleDebugAndroidTest' }
$proxyArguments = @()
if ($UseSessionProxy) {
    foreach ($protocol in @('http', 'https')) {
        $proxyValue = [Environment]::GetEnvironmentVariable($protocol.ToUpperInvariant() + '_PROXY')
        if ($proxyValue) {
            $proxyUri = [Uri]$proxyValue
            if ($proxyUri.UserInfo) { throw '带身份凭据的代理请在本机 Gradle 用户配置中设置。' }
            $proxyArguments += "-D$protocol.proxyHost=$($proxyUri.Host)"
            $proxyArguments += "-D$protocol.proxyPort=$($proxyUri.Port)"
        }
    }
}
Push-Location $projectRoot
try {
    & .\gradlew.bat @tasks @proxyArguments --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE。" }
    $destinationDir = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $destinationDir -Force | Out-Null
    $metadataFile = Join-Path $projectRoot "app\build\outputs\apk\$variant\output-metadata.json"
    $metadata = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json
    $apkMetadata = @($metadata.elements)[0]
    $version = $apkMetadata.versionName
    $suffix = if ($variant -eq 'debug') { '-debug' } else { '' }
    $destination = Join-Path $destinationDir "ErgoushushuToolbox-$version$suffix.apk"
    $builtApk = Join-Path (Split-Path $metadataFile -Parent) $apkMetadata.outputFile
    Copy-Item -LiteralPath $builtApk -Destination $destination -Force
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$destination.sha256", "$hash  $([IO.Path]::GetFileName($destination))`n", [Text.UTF8Encoding]::new($false))
    Write-Output "APK: $destination"
    if ($DeviceTests) {
        $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        if (-not $DeviceSerial) { $DeviceSerial = $env:ANDROID_SERIAL }
        if (-not $DeviceSerial) {
            $devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
            if ($devices.Count -ne 1) { throw '请用 -DeviceSerial 指定唯一的测试设备。' }
            $DeviceSerial = $devices[0]
        }
        & $adb -s $DeviceSerial install -r $destination
        if ($LASTEXITCODE -ne 0) { throw '测试应用安装失败。' }
        & $adb -s $DeviceSerial install -r (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
        if ($LASTEXITCODE -ne 0) { throw '测试包安装失败。' }
        $testOutput = & $adb -s $DeviceSerial shell am instrument -w -r com.ergoushushu.toolbox.test/androidx.test.runner.AndroidJUnitRunner
        $testOutput | Set-Content -LiteralPath (Join-Path $destinationDir 'instrumentation-results.txt') -Encoding utf8
        $testOutput | Write-Output
        if ($LASTEXITCODE -ne 0 -or ($testOutput -join "`n") -notmatch '(?m)^OK \(\d+ tests?\)') {
            throw '安卓设备测试未通过，详情见 dist/instrumentation-results.txt。'
        }
        & $adb -s $DeviceSerial shell am force-stop com.ergoushushu.toolbox
    }
} finally {
    Pop-Location
}
