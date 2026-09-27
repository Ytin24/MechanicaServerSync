param(
    [string[]] $Targets,
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $Java25Home,
    [switch] $Clean,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$catalog = Get-Content -LiteralPath (Join-Path $root 'targets.json') -Raw | ConvertFrom-Json
$selected = @($catalog | Where-Object { -not $Targets -or $_.id -in $Targets })
if ($selected.Count -eq 0 -or ($Targets -and @($Targets | Where-Object { $_ -notin $catalog.id }).Count -gt 0)) { throw 'Unknown bridge target.' }
$previousJavaHome = $env:JAVA_HOME
try {
    if (-not $SkipBuild) {
        foreach ($project in @($selected | Group-Object project)) {
            $directory = [IO.Path]::GetFullPath((Join-Path $root $project.Name))
            if (-not $directory.Equals($root, [StringComparison]::OrdinalIgnoreCase) -and
                -not $directory.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Project escapes the bridge repository.' }
            $major = if ($project.Group[0].minecraft -match '^26\.') { 25 } else { 21 }
            $jdk = if ($major -eq 25) { $Java25Home } else { $JavaHome }
            if (-not $jdk) { $jdk = [Environment]::GetEnvironmentVariable('JAVA_HOME_' + $major + '_X64') }
            if (-not $jdk -and $env:ProgramFiles) {
                $installed = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
                if (Test-Path -LiteralPath $installed) {
                    $jdk = Get-ChildItem -LiteralPath $installed -Directory -Filter ('jdk-' + $major + '*') |
                        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
                }
            }
            if (-not $jdk -or -not (Test-Path -LiteralPath (Join-Path $jdk 'bin/java.exe'))) { throw ('JDK ' + $major + ' is required for ' + $project.Name) }
            $env:JAVA_HOME = $jdk
            $wrapper = Join-Path $directory 'gradlew.bat'
            if (-not (Test-Path -LiteralPath $wrapper)) { $wrapper = Join-Path $root 'gradlew.bat' }
            $arguments = @('--no-daemon', '--console=plain', '-p', $directory)
            if ($Clean) { $arguments += 'clean' }
            $arguments += @('check', 'build')
            $previousErrorAction = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                & $wrapper @arguments
            }
            finally { $ErrorActionPreference = $previousErrorAction }
            if ($LASTEXITCODE -ne 0) { throw ('Bridge build failed: ' + $project.Name) }
        }
    }
    foreach ($target in $selected) {
        foreach ($relative in @($target.file, $target.fixture)) {
            $artifact = Join-Path $root $relative
            if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw ('Bridge artifact is missing: ' + $relative) }
            $packFormat = $target.PSObject.Properties['resourcePackFormat']
            if ($packFormat) {
                $archive = [IO.Compression.ZipFile]::OpenRead($artifact)
                try {
                    $metadata = $archive.GetEntry('pack.mcmeta')
                    if (-not $metadata) { throw ('pack.mcmeta is missing: ' + $relative) }
                    $reader = [IO.StreamReader]::new($metadata.Open())
                    try { $pack = $reader.ReadToEnd() | ConvertFrom-Json }
                    finally { $reader.Dispose() }
                    if ($pack.pack.pack_format -ne $packFormat.Value) { throw ('Incorrect resource pack format: ' + $relative) }
                }
                finally { $archive.Dispose() }
            }
        }
    }
    $distribution = Join-Path $root 'dist'
    $fixtures = Join-Path $distribution 'fixtures'
    New-Item -ItemType Directory -Path $distribution, $fixtures -Force | Out-Null
    $packages = @()
    foreach ($target in $catalog) {
        $artifact = Join-Path $root $target.file
        $fixture = Join-Path $root $target.fixture
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf) -or -not (Test-Path -LiteralPath $fixture -PathType Leaf)) { continue }
        $name = [IO.Path]::GetFileName($artifact)
        Copy-Item -LiteralPath $artifact -Destination (Join-Path $distribution $name) -Force
        Copy-Item -LiteralPath $fixture -Destination (Join-Path $fixtures ([IO.Path]::GetFileName($fixture))) -Force
        $packages += [ordered]@{ minecraft = $target.minecraft; loader = $target.loader; minimumLoaderVersion = $target.minimumLoaderVersion
            fileName = $name; sha512 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA512).Hash.ToLowerInvariant() }
    }
    $temporary = Join-Path $distribution 'manifest.json.tmp'
    ConvertTo-Json -InputObject @($packages) -Depth 5 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination (Join-Path $distribution 'manifest.json') -Force
    Write-Output ('Packaged ' + $packages.Count + ' bridge targets in ' + $distribution)
}
finally { $env:JAVA_HOME = $previousJavaHome }
