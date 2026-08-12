param(
    [Parameter(Mandatory = $true)]
    [string]$NativeReleaseDir,

    [Parameter(Mandatory = $true)]
    [string]$OutputRoot
)

$ErrorActionPreference = "Stop"
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot "build"))

function Resolve-ProjectPath([string]$Value) {
    if ([IO.Path]::IsPathRooted($Value)) {
        return [IO.Path]::GetFullPath($Value)
    }
    return [IO.Path]::GetFullPath((Join-Path $projectRoot $Value))
}

$generatedDirectoryNames = @("bin", "obj")
function Copy-InstallerSource([string]$Source, [string]$Destination) {
    New-Item -ItemType Directory -Path $Destination | Out-Null
    foreach ($entry in Get-ChildItem -LiteralPath $Source -Force) {
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Installer source copy rejects links and reparse points: $($entry.FullName)"
        }

        $target = Join-Path $Destination $entry.Name
        if ($entry.PSIsContainer) {
            if ($generatedDirectoryNames -contains $entry.Name) {
                continue
            }
            Copy-InstallerSource $entry.FullName $target
        } else {
            Copy-Item -LiteralPath $entry.FullName -Destination $target
        }
    }
}

$nativeRelease = Resolve-ProjectPath $NativeReleaseDir
$output = Resolve-ProjectPath $OutputRoot
$buildPrefix = $buildRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($buildPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputRoot must be a child of the project build directory."
}
if (-not (Test-Path -LiteralPath $nativeRelease -PathType Container)) {
    throw "NativeReleaseDir must be an existing directory."
}
if (Test-Path -LiteralPath $output) {
    throw "OutputRoot must not already exist."
}

New-Item -ItemType Directory -Path $output | Out-Null
$hashes = @()
foreach ($runName in @("a", "b")) {
    $runRoot = Join-Path $output $runName
    New-Item -ItemType Directory -Path $runRoot | Out-Null
    $installerRoot = Join-Path $runRoot "installer"
    Copy-InstallerSource (Join-Path $projectRoot "installer") $installerRoot
    $project = Join-Path $installerRoot "src/Apollo.NativeAssist.Installer/Apollo.NativeAssist.Installer.csproj"
    $publish = Join-Path $runRoot "publish"

    & dotnet clean $project -c Release -r win-x64
    if ($LASTEXITCODE -ne 0) { throw "dotnet clean failed for isolated run $runName" }

    & dotnet publish $project -c Release -r win-x64 --self-contained true `
        -p:PublishSingleFile=true -p:PublishTrimmed=false `
        -p:Deterministic=true -p:ContinuousIntegrationBuild=true `
        "-p:PathMap=$runRoot=/src" `
        "-p:NativeReleaseDir=$nativeRelease" -o $publish
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed for isolated run $runName" }

    $executable = Join-Path $publish "Apollo.NativeAssist.Installer.exe"
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "isolated publish $runName did not produce the installer executable"
    }
    $hashes += (Get-FileHash -Algorithm SHA256 -LiteralPath $executable).Hash.ToLowerInvariant()
}

if ($hashes[0] -ne $hashes[1]) {
    throw "installer reproducibility mismatch"
}

$releaseAssets = Join-Path $output "release-assets"
New-Item -ItemType Directory -Path $releaseAssets | Out-Null
Copy-Item -LiteralPath (Join-Path $output "a/publish/Apollo.NativeAssist.Installer.exe") `
    -Destination (Join-Path $releaseAssets "Apollo.PZ.MP.Sync.Setup-win-x64.exe")
Write-Output "PASS isolated installer publishes SHA256=$($hashes[0])"
