[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^https?://')]
    [string]$HubUrl,

    [Parameter(Mandatory)]
    [string]$SourceFile,

    [ValidateRange(1, [int]::MaxValue)]
    [int]$CodeId,

    [string]$BackupDirectory,

    [string]$ValidationScript,

    [ValidateRange(5, 300)]
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'PowerShellAppDeploy.psm1'
Import-Module $modulePath -Force

$arguments = @{
    HubUrl        = $HubUrl
    SourceFile    = $SourceFile
    TimeoutSeconds = $TimeoutSeconds
}
if ($PSBoundParameters.ContainsKey('CodeId')) { $arguments.CodeId = $CodeId }
if ($BackupDirectory) { $arguments.BackupDirectory = $BackupDirectory }
if ($ValidationScript) { $arguments.ValidationScript = $ValidationScript }
if ($WhatIfPreference) { $arguments.WhatIf = $true }
if ($PSBoundParameters.ContainsKey('Confirm')) { $arguments.Confirm = $PSBoundParameters.Confirm }

Invoke-HubitatAppDeploy @arguments
