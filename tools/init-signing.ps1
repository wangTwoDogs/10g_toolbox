param([string]$JavaHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingDirectory = Join-Path $projectRoot '.signing'
$propertiesFile = Join-Path $projectRoot 'signing.properties'
$keystoreFile = Join-Path $signingDirectory 'toolbox-release.p12'
if ((Test-Path -LiteralPath $propertiesFile) -and (Test-Path -LiteralPath $keystoreFile)) {
    Write-Output '已有发布签名，将继续复用。'
    exit 0
}
if ((Test-Path -LiteralPath $propertiesFile) -or (Test-Path -LiteralPath $keystoreFile)) {
    throw '已存在部分签名文件，停止以避免覆盖。请恢复原有密钥与签名配置。'
}
if (-not $JavaHome) {
    $portableRoot = Join-Path (Split-Path $projectRoot -Parent) '.toolchains\android'
    $jdk = Get-ChildItem -LiteralPath $portableRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) { throw '请配置 JAVA_HOME，指向 JDK17。' }
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
$passwordBytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($passwordBytes) } finally { $random.Dispose() }
$password = [Convert]::ToBase64String($passwordBytes)
$previousPasswordEnvironment = [Environment]::GetEnvironmentVariable('TOOLBOX_SIGNING_PASSWORD', 'Process')
try {
    [Environment]::SetEnvironmentVariable('TOOLBOX_SIGNING_PASSWORD', $password, 'Process')
    & $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $keystoreFile `
        -alias toolbox-release -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 `
        -dname 'CN=Ergoushushu Toolbox, OU=Android, O=Ergoushushu, C=CN' `
        -storepass:env TOOLBOX_SIGNING_PASSWORD -keypass:env TOOLBOX_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw '发布密钥创建失败。' }
    $properties = "storeFile=.signing/toolbox-release.p12`nstorePassword=$password`nkeyAlias=toolbox-release`nkeyPassword=$password`n"
    [IO.File]::WriteAllText($propertiesFile, $properties, [Text.UTF8Encoding]::new($false))
    Write-Output '已创建本机固定发布签名。密钥和签名配置均不提交到 Git，请保留用于后续更新。'
} finally {
    [Environment]::SetEnvironmentVariable('TOOLBOX_SIGNING_PASSWORD', $previousPasswordEnvironment, 'Process')
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
    $password = $null
    $properties = $null
}
