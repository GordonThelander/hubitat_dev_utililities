$modulePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'PowerShellAppDeploy.psm1'
Import-Module $modulePath -Force

Describe 'Get-HubitatAppSourceMetadata' {
    It 'reads single-quoted metadata' {
        $result = Get-HubitatAppSourceMetadata "definition(name: 'Example App', namespace: 'example.dev', author: 'Test')"
        $result.Name | Should Be 'Example App'
        $result.Namespace | Should Be 'example.dev'
    }

    It 'reads double-quoted metadata across lines' {
        $source = @'
definition(
    name: "Example App",
    namespace: "example.dev",
    author: "Test"
)
'@
        $result = Get-HubitatAppSourceMetadata $source
        $result.Name | Should Be 'Example App'
        $result.Namespace | Should Be 'example.dev'
    }

    It 'resolves definition metadata from quoted String constants' {
        $source = @'
@Field static final String APP_NAME = 'Example App'
static final String APP_NAMESPACE = 'example.dev'

definition(
    name: APP_NAME,
    namespace: APP_NAMESPACE,
    author: 'Test'
)
'@
        $result = Get-HubitatAppSourceMetadata $source
        $result.Name | Should Be 'Example App'
        $result.Namespace | Should Be 'example.dev'
    }

    It 'refuses non-literal metadata' {
        $threw = $false
        try {
            Get-HubitatAppSourceMetadata 'definition(name: APP_NAME, namespace: APP_NAMESPACE)'
        } catch {
            $threw = $true
        }
        $threw | Should Be $true
    }
}

Describe 'Get-TextSha256' {
    It 'is deterministic and distinguishes different source' {
        (Get-TextSha256 'alpha') | Should Be (Get-TextSha256 'alpha')
        (Get-TextSha256 'alpha') | Should Not Be (Get-TextSha256 'beta')
    }
}

Describe 'Invoke-HubitatAppDeploy' {
    BeforeEach {
        $sourcePath = Join-Path $TestDrive 'example.groovy'
        @'
definition(name: 'Example App', namespace: 'example.dev', author: 'Test')
def updated() { }
'@ | Set-Content -LiteralPath $sourcePath -NoNewline
    }

    It 'performs a dry run without posting or creating a backup' {
        Mock Invoke-RestMethod -ModuleName PowerShellAppDeploy {
            if ($Uri -like '*/hub2/appsList') {
                return [pscustomobject]@{ userAppTypes = @([pscustomobject]@{ id = 17; name = 'Example App' }) }
            }
            if ($Uri -like '*/app/ajax/code*') {
                return [pscustomobject]@{
                    version = 4
                    source = "definition(name: 'Example App', namespace: 'example.dev', author: 'Test')"
                }
            }
            throw "Unexpected request: $Method $Uri"
        }

        $result = Invoke-HubitatAppDeploy -HubUrl 'http://hub.local' -SourceFile $sourcePath -WhatIf
        $result.Status | Should Be 'what-if'
        Assert-MockCalled Invoke-RestMethod -ModuleName PowerShellAppDeploy -ParameterFilter { $Method -eq 'Post' } -Times 0
        (Test-Path (Join-Path $TestDrive '.hubitat-app-backups')) | Should Be $false
    }

    It 'refuses an explicit ID with a different namespace' {
        Mock Invoke-RestMethod -ModuleName PowerShellAppDeploy {
            [pscustomobject]@{
                version = 4
                source = "definition(name: 'Example App', namespace: 'someone.else', author: 'Test')"
            }
        }

        $threw = $false
        try {
            Invoke-HubitatAppDeploy -HubUrl 'http://hub.local' -SourceFile $sourcePath -CodeId 17 -WhatIf
        } catch {
            $threw = $true
        }
        $threw | Should Be $true
        Assert-MockCalled Invoke-RestMethod -ModuleName PowerShellAppDeploy -ParameterFilter { $Method -eq 'Post' } -Times 0
    }

    It 'refuses ambiguous name and namespace matches' {
        Mock Invoke-RestMethod -ModuleName PowerShellAppDeploy {
            if ($Uri -like '*/hub2/appsList') {
                return [pscustomobject]@{
                    userAppTypes = @(
                        [pscustomobject]@{ id = 17; name = 'Example App' },
                        [pscustomobject]@{ id = 18; name = 'Example App' }
                    )
                }
            }
            return [pscustomobject]@{
                version = 4
                source = "definition(name: 'Example App', namespace: 'example.dev', author: 'Test')"
            }
        }

        $threw = $false
        try {
            Invoke-HubitatAppDeploy -HubUrl 'http://hub.local' -SourceFile $sourcePath -WhatIf
        } catch {
            $threw = $true
        }
        $threw | Should Be $true
        Assert-MockCalled Invoke-RestMethod -ModuleName PowerShellAppDeploy -ParameterFilter { $Method -eq 'Post' } -Times 0
    }

    It 'does not back up or post unchanged source' {
        $localSource = [IO.File]::ReadAllText($sourcePath)
        $global:HubDeployUnchangedSource = $localSource
        Mock Invoke-RestMethod -ModuleName PowerShellAppDeploy {
            if ($Uri -like '*/hub2/appsList') {
                return [pscustomobject]@{ userAppTypes = @([pscustomobject]@{ id = 17; name = 'Example App' }) }
            }
            return [pscustomobject]@{ version = 4; source = $global:HubDeployUnchangedSource }
        }

        $result = Invoke-HubitatAppDeploy -HubUrl 'http://hub.local' -SourceFile $sourcePath -Confirm:$false
        $result.Status | Should Be 'unchanged'
        Assert-MockCalled Invoke-RestMethod -ModuleName PowerShellAppDeploy -ParameterFilter { $Method -eq 'Post' } -Times 0
        Remove-Variable HubDeployUnchangedSource -Scope Global -ErrorAction SilentlyContinue
    }

    It 'backs up, posts and verifies a changed source' {
        $global:HubDeployTestCodeReads = 0
        $global:HubDeployTestSourcePath = $sourcePath
        Mock Invoke-RestMethod -ModuleName PowerShellAppDeploy {
            if ($Uri -like '*/hub2/appsList') {
                return [pscustomobject]@{ userAppTypes = @([pscustomobject]@{ id = 17; name = 'Example App' }) }
            }
            if ($Method -eq 'Post') {
                return [pscustomobject]@{ status = 'success' }
            }
            if ($Uri -like '*/app/ajax/code*') {
                $global:HubDeployTestCodeReads++
                if ($global:HubDeployTestCodeReads -eq 1) {
                    return [pscustomobject]@{
                        version = 4
                        source = "definition(name: 'Example App', namespace: 'example.dev', author: 'Test')"
                    }
                }
                return [pscustomobject]@{
                    version = 5
                    source = [IO.File]::ReadAllText($global:HubDeployTestSourcePath)
                }
            }
            throw "Unexpected request: $Method $Uri"
        }

        $backupDirectory = Join-Path $TestDrive 'backups'
        $result = Invoke-HubitatAppDeploy -HubUrl 'http://hub.local' -SourceFile $sourcePath `
            -BackupDirectory $backupDirectory -Confirm:$false

        $result.Status | Should Be 'deployed'
        $result.PreviousRevision | Should Be 4
        $result.NewRevision | Should Be 5
        (Test-Path $result.BackupPath) | Should Be $true
        Assert-MockCalled Invoke-RestMethod -ModuleName PowerShellAppDeploy -ParameterFilter { $Method -eq 'Post' } -Times 1
        Remove-Variable HubDeployTestCodeReads -Scope Global -ErrorAction SilentlyContinue
        Remove-Variable HubDeployTestSourcePath -Scope Global -ErrorAction SilentlyContinue
    }
}
