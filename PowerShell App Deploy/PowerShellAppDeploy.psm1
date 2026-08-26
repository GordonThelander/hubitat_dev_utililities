Set-StrictMode -Version Latest

function Resolve-GroovyDefinitionField {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Source,

        [Parameter(Mandatory)]
        [ValidateSet('name', 'namespace')]
        [string]$Field
    )

    $definitionMatch = [regex]::Match($Source, '(?s)\bdefinition\s*\((?<body>.{0,5000})')
    if (-not $definitionMatch.Success) { return $null }
    $body = $definitionMatch.Groups['body'].Value
    $fieldPattern = '\b' + [regex]::Escape($Field) + '\s*:\s*(?:(?<quote>["''])(?<literal>[^"'']*)\k<quote>|(?<identifier>[A-Za-z_][A-Za-z0-9_]*))'
    $fieldMatch = [regex]::Match($body, $fieldPattern)
    if (-not $fieldMatch.Success) { return $null }
    if ($fieldMatch.Groups['literal'].Success) {
        return $fieldMatch.Groups['literal'].Value
    }

    $identifier = $fieldMatch.Groups['identifier'].Value
    $constantPattern = '(?m)^\s*(?:@Field\s+)?(?:static\s+)?(?:final\s+)?String\s+' +
        [regex]::Escape($identifier) + '\s*=\s*(?<quote>["''])(?<literal>[^"'']*)\k<quote>\s*$'
    $constantMatches = [regex]::Matches($Source, $constantPattern)
    if ($constantMatches.Count -ne 1) { return $null }
    $constantMatches[0].Groups['literal'].Value
}

function Get-HubitatAppSourceMetadata {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Source
    )

    $name = Resolve-GroovyDefinitionField -Source $Source -Field name
    $namespace = Resolve-GroovyDefinitionField -Source $Source -Field namespace

    if ([string]::IsNullOrWhiteSpace($name)) {
        throw 'Could not resolve the app name from definition() to a quoted literal or quoted String constant.'
    }
    if ([string]::IsNullOrWhiteSpace($namespace)) {
        throw 'Could not resolve the namespace from definition() to a quoted literal or quoted String constant.'
    }

    [pscustomobject]@{
        Name      = $name
        Namespace = $namespace
    }
}

function Get-TextSha256 {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Text
    )

    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash($bytes)
        ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Get-HubitatCodeRecord {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$HubBase,

        [Parameter(Mandatory)]
        [int]$CodeId,

        [Parameter(Mandatory)]
        [int]$TimeoutSeconds
    )

    Invoke-RestMethod -Uri "$HubBase/app/ajax/code?id=$CodeId" -Method Get -TimeoutSec $TimeoutSeconds
}

function Resolve-HubitatAppTarget {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$HubBase,

        [Parameter(Mandatory)]
        [string]$Name,

        [Parameter(Mandatory)]
        [string]$Namespace,

        [ValidateRange(0, [int]::MaxValue)]
        [int]$CodeId = 0,

        [Parameter(Mandatory)]
        [int]$TimeoutSeconds
    )

    if ($CodeId -gt 0) {
        $record = Get-HubitatCodeRecord -HubBase $HubBase -CodeId $CodeId -TimeoutSeconds $TimeoutSeconds
        if ([string]::IsNullOrWhiteSpace([string]$record.source)) {
            throw "Apps Code entry $CodeId returned empty source."
        }
        $metadata = Get-HubitatAppSourceMetadata -Source ([string]$record.source)
        if ($metadata.Name -ne $Name -or $metadata.Namespace -ne $Namespace) {
            throw "Apps Code entry $CodeId is '$($metadata.Namespace):$($metadata.Name)', not '$Namespace`:$Name'."
        }
        return [pscustomobject]@{ Id = $CodeId; Record = $record }
    }

    $inventory = Invoke-RestMethod -Uri "$HubBase/hub2/appsList" -Method Get -TimeoutSec $TimeoutSeconds
    $nameMatches = @($inventory.userAppTypes | Where-Object { [string]$_.name -eq $Name })
    if ($nameMatches.Count -eq 0) {
        throw "No existing Apps Code entry named '$Name' was found. This tool does not create Apps Code entries."
    }

    $exactMatches = @()
    foreach ($candidate in $nameMatches) {
        $candidateId = [int]$candidate.id
        $record = Get-HubitatCodeRecord -HubBase $HubBase -CodeId $candidateId -TimeoutSeconds $TimeoutSeconds
        if ([string]::IsNullOrWhiteSpace([string]$record.source)) { continue }
        try {
            $metadata = Get-HubitatAppSourceMetadata -Source ([string]$record.source)
        } catch {
            continue
        }
        if ($metadata.Name -eq $Name -and $metadata.Namespace -eq $Namespace) {
            $exactMatches += [pscustomobject]@{ Id = $candidateId; Record = $record }
        }
    }

    if ($exactMatches.Count -ne 1) {
        throw "Expected exactly one Apps Code entry for '$Namespace`:$Name', found $($exactMatches.Count). Nothing was changed."
    }
    $exactMatches[0]
}

function Invoke-HubitatAppDeploy {
    [CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
    param(
        [Parameter(Mandatory)]
        [ValidatePattern('^https?://')]
        [string]$HubUrl,

        [Parameter(Mandatory)]
        [string]$SourceFile,

        [ValidateRange(0, [int]::MaxValue)]
        [int]$CodeId = 0,

        [string]$BackupDirectory,

        [string]$ValidationScript,

        [ValidateRange(5, 300)]
        [int]$TimeoutSeconds = 60
    )

    $sourcePath = (Resolve-Path -LiteralPath $SourceFile -ErrorAction Stop).Path
    $source = [IO.File]::ReadAllText($sourcePath)
    $metadata = Get-HubitatAppSourceMetadata -Source $source

    if ($ValidationScript) {
        $validationPath = (Resolve-Path -LiteralPath $ValidationScript -ErrorAction Stop).Path
        $global:LASTEXITCODE = 0
        & $validationPath $sourcePath | Out-Host
        if (-not $? -or $LASTEXITCODE -ne 0) {
            throw "Validation script failed with exit code $LASTEXITCODE."
        }
    }

    $hubBase = $HubUrl.TrimEnd('/')
    $target = Resolve-HubitatAppTarget -HubBase $hubBase -Name $metadata.Name `
        -Namespace $metadata.Namespace -CodeId $CodeId -TimeoutSeconds $TimeoutSeconds
    $current = $target.Record
    $appId = [int]$target.Id
    $currentSource = [string]$current.source

    $localHash = Get-TextSha256 -Text $source
    $hubHash = Get-TextSha256 -Text $currentSource
    Write-Host "Target: $($metadata.Namespace):$($metadata.Name), Apps Code ID $appId, revision $($current.version)"
    Write-Host "Local SHA-256: $localHash"
    Write-Host "Hub SHA-256:   $hubHash"

    if ($localHash -eq $hubHash) {
        Write-Host 'Hub source already matches the local source. Nothing to deploy.'
        return [pscustomobject]@{
            Status = 'unchanged'; CodeId = $appId; BackupPath = $null
            PreviousRevision = [int]$current.version; NewRevision = [int]$current.version
        }
    }

    $targetDescription = "$($metadata.Namespace):$($metadata.Name), Apps Code ID $appId"
    if (-not $PSCmdlet.ShouldProcess($targetDescription, 'Back up and replace Apps Code source')) {
        return [pscustomobject]@{
            Status = 'what-if'; CodeId = $appId; BackupPath = $null
            PreviousRevision = [int]$current.version; NewRevision = $null
        }
    }

    if ([string]::IsNullOrWhiteSpace($BackupDirectory)) {
        $BackupDirectory = Join-Path (Split-Path -Parent $sourcePath) '.hubitat-app-backups'
    }
    $backupRoot = [IO.Path]::GetFullPath($BackupDirectory)
    [IO.Directory]::CreateDirectory($backupRoot) | Out-Null
    $safeName = ($metadata.Name -replace '[^A-Za-z0-9._-]+', '-')
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupPath = Join-Path $backupRoot "$safeName-app-$appId-rev-$($current.version)-$stamp.groovy"
    [IO.File]::WriteAllText($backupPath, $currentSource, [Text.UTF8Encoding]::new($false))

    $body = @{
        id      = $appId
        version = [int]$current.version
        source  = $source
    }
    $result = Invoke-RestMethod -Uri "$hubBase/app/ajax/update" -Method Post -Body $body -TimeoutSec $TimeoutSeconds
    $statusProperty = $result.PSObject.Properties['status']
    if ($null -ne $statusProperty -and $statusProperty.Value -and [string]$statusProperty.Value -ne 'success') {
        throw "Hub rejected the update: $($result | ConvertTo-Json -Compress -Depth 5). Backup: $backupPath"
    }

    $saved = Get-HubitatCodeRecord -HubBase $hubBase -CodeId $appId -TimeoutSeconds $TimeoutSeconds
    $savedHash = Get-TextSha256 -Text ([string]$saved.source)
    if ($savedHash -ne $localHash) {
        throw "Post-deployment source verification failed. Backup: $backupPath"
    }
    if ([int]$saved.version -le [int]$current.version) {
        throw "Source matches, but the hub revision did not increase. Backup: $backupPath"
    }

    Write-Host "Deployed and verified revision $($current.version) -> $($saved.version)."
    Write-Host "Backup: $backupPath"
    [pscustomobject]@{
        Status = 'deployed'; CodeId = $appId; BackupPath = $backupPath
        PreviousRevision = [int]$current.version; NewRevision = [int]$saved.version
    }
}

Export-ModuleMember -Function Get-HubitatAppSourceMetadata, Get-TextSha256, Invoke-HubitatAppDeploy
