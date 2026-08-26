# PowerShell App Deploy

A safety-focused PowerShell utility for updating an existing Hubitat **Apps Code** definition from
a local Groovy source file.

It is intended for app developers who want a repeatable alternative to copying source into the
Hubitat web editor. It does not install an app instance, configure an app, create an Apps Code entry,
or replace Hubitat Package Manager.

## Requirements

- Windows PowerShell 5.1 or PowerShell 7+
- Network access to the hub's local administration interface
- An existing user Apps Code entry matching the source's `definition()` name and namespace
- A hub configuration that permits the local administrative requests used by this tool

The tool uses Hubitat's internal administration endpoints. They are not a documented public API and
may change in a future platform release. Always use `-WhatIf` first after a hub update.

## Safety model

Before writing, the utility:

1. Resolves the app name and namespace from the local `definition()` block. Quoted values and
   identifiers backed by one quoted `String` constant are supported; computed metadata is refused.
2. Finds an exact matching Apps Code entry.
3. Refuses zero or ambiguous matches.
4. Downloads the current hub source.
5. Displays local and hub SHA-256 hashes.
6. Uses the current hub revision in the update request.
7. Creates a UTF-8 backup before upload.
8. Reads the saved source back and verifies its hash and revision.

There is deliberately no broad force option that bypasses identity or verification failures.

## First use

Run a dry run:

```powershell
.\Deploy-HubitatApp.ps1 `
  -HubUrl 'http://192.168.1.100' `
  -SourceFile '.\apps\my_app.groovy' `
  -WhatIf
```

If the target and hashes are correct, deploy:

```powershell
.\Deploy-HubitatApp.ps1 `
  -HubUrl 'http://192.168.1.100' `
  -SourceFile '.\apps\my_app.groovy'
```

PowerShell's standard confirmation behavior applies because deployment is declared a high-impact
operation. Use `-Confirm:$false` only in an already controlled automation workflow.

## Explicit Apps Code ID

If you already know the Apps Code ID, supply it:

```powershell
.\Deploy-HubitatApp.ps1 `
  -HubUrl 'http://192.168.1.100' `
  -SourceFile '.\apps\my_app.groovy' `
  -CodeId 1234 `
  -WhatIf
```

The tool still downloads that entry and verifies its name and namespace before allowing deployment.

## Optional validation hook

Provide a PowerShell validation script when your project has its own checks:

```powershell
.\Deploy-HubitatApp.ps1 `
  -HubUrl 'http://192.168.1.100' `
  -SourceFile '.\apps\my_app.groovy' `
  -ValidationScript '.\validate-app.ps1' `
  -WhatIf
```

The resolved source path is passed as the validation script's first positional argument. A non-zero
exit code stops deployment.

## Parameters

| Parameter | Required | Description |
| --- | --- | --- |
| `HubUrl` | Yes | Hub base URL, normally a local HTTP address. |
| `SourceFile` | Yes | Local Groovy app source. |
| `CodeId` | No | Explicit Apps Code ID, still identity-checked. |
| `BackupDirectory` | No | Backup destination. Defaults beside the source in `.hubitat-app-backups`. |
| `ValidationScript` | No | Project validation script run before hub discovery. |
| `TimeoutSeconds` | No | Request timeout from 5 to 300 seconds. Default 60. |
| `WhatIf` | No | Performs validation, discovery and comparison without writing. |

## Backup and recovery

Every changed deployment creates a backup before the POST. If deployment or post-save verification
fails, the error reports the backup path. Restore by selecting that backup as `SourceFile` and
running the same dry-run and deployment sequence.

Backups may contain private source code. The default backup directory should be ignored by the app
repository in which you use this tool.

## Scope

Version 1 supports user **Apps Code** only. Driver Code and Libraries Code use different inventory
and update surfaces and should be added only after their behavior is independently verified.

## Security

- Do not commit hub addresses, cookies, credentials or exported administrative responses.
- Use only on hubs you own or are authorized to administer.
- Prefer a trusted local network.
- Review the target and hashes shown by `-WhatIf` before the first deployment.
