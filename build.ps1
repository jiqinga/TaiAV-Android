param(
    [switch]$Verify,
    [string]$BuildProxy = ''
)

$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot

function Get-InstalledDirectories([string]$parentDirectory) {
    if (Test-Path -LiteralPath $parentDirectory) {
        Get-ChildItem -LiteralPath $parentDirectory -Directory | ForEach-Object { $_.FullName }
    }
}

$javaCandidates = @($env:JAVA_HOME) +
    @(Get-InstalledDirectories "$env:LOCALAPPDATA\mise\installs\java-vfox") +
    @(Get-InstalledDirectories "$env:LOCALAPPDATA\mise\installs\java") +
    @('C:\Program Files\Android\Android Studio\jbr')
$jdkDirectory = $javaCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe'))
} | Select-Object -First 1
if (-not $jdkDirectory) { throw '请安装 JDK 17 或更高版本，并设置 JAVA_HOME。' }
$env:JAVA_HOME = $jdkDirectory

$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") +
    @(Get-InstalledDirectories "$env:LOCALAPPDATA\mise\installs\android-sdk")
$sdkDirectory = $sdkCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platform-tools\adb.exe'))
} | Select-Object -First 1
if ($sdkDirectory) {
    $sdkProperty = 'sdk.dir=' + $sdkDirectory.Replace('\', '/').Replace(':', '\:')
    Set-Content -LiteralPath (Join-Path $projectDirectory 'local.properties') -Value $sdkProperty -Encoding ascii -NoNewline
}

$gradleArguments = @()
if ($BuildProxy) {
    $proxyUri = [Uri]$BuildProxy
    if ($proxyUri.Scheme -ne 'http' -or $proxyUri.UserInfo) { throw 'BuildProxy 请使用无认证的 HTTP 代理地址。' }
    $gradleArguments += @("-Dhttp.proxyHost=$($proxyUri.Host)", "-Dhttp.proxyPort=$($proxyUri.Port)",
        "-Dhttps.proxyHost=$($proxyUri.Host)", "-Dhttps.proxyPort=$($proxyUri.Port)")
}
$gradleArguments += 'assembleDebug'
if ($Verify) { $gradleArguments += @('lintDebug', ':network:checkNetwork') }

Push-Location $projectDirectory
try {
    & .\gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建未通过，退出码 $LASTEXITCODE" }
    $outputDirectory = Join-Path $projectDirectory 'dist'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $appBuildText = Get-Content -LiteralPath (Join-Path $projectDirectory 'app\build.gradle.kts') -Raw
    $appVersion = [regex]::Match($appBuildText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    if (-not $appVersion) { throw 'app/build.gradle.kts 中缺少版本号。' }
    $apkPath = Join-Path $outputDirectory "TaiAV-$appVersion-debug.apk"
    Copy-Item -LiteralPath (Join-Path $projectDirectory 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apkPath -Force
    if ($Verify) {
        $verificationDirectory = Join-Path $projectDirectory 'artifacts'
        New-Item -ItemType Directory -Path $verificationDirectory -Force | Out-Null
        [ordered]@{
            version = $appVersion
            apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
            checks = @('assembleDebug', 'lintDebug', ':network:checkNetwork')
            deviceTestsRun = $false
        } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $verificationDirectory "build-verification-$appVersion.json") -Encoding utf8
    }
    Write-Output "APK: $apkPath"
} finally { Pop-Location }
