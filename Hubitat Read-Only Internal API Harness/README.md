# Hubitat Read-Only Internal API Harness

A reusable Hubitat Groovy Library and verification harness for undocumented, read-only internal endpoints used by the Hubitat administration interface.

The utility provides a single abstraction for introspection under `/hub`, `/hub2`, `/device`, `/installedapp`, `/app`, `/driver`, and `/library`. It deliberately excludes code updates, deletion, reboot, radio/network configuration, factory reset, and every other mutation or administrative operation.

## Important compatibility warning

These are not published Hubitat developer APIs. Hubitat provides no compatibility guarantee for their paths, response shapes, authentication behavior, or continued availability.

Consumers must capability-test endpoints at runtime and treat failure as “not available on this hub or firmware,” rather than assuming a particular firmware version supports them.

Hub Login Security can cause a loopback request to return a login page instead of JSON. Use `hiaProbeCompatibility()` before relying on the endpoint catalogue.

## Security boundary

Read-only does not mean non-sensitive.

Several endpoints expose source code, hub identity, device metadata, or installed-application state. `/installedapp/statusJson/{id}` can include raw settings belonging to another application, including OAuth tokens, passwords, API keys, account identifiers, and private cloud URLs.

- Request only the fields the caller needs.
- Do not log or persist complete endpoint responses.
- Do not expose endpoint responses through an unauthenticated mapping.
- Redact data before displaying, exporting, or logging it.
- Treat App Code, driver, and library source endpoints as sensitive even though they are read-only.

The library includes two defensive helpers:

- `hiaRedactUrl(url)` masks common secret-bearing query parameters and bearer tokens.
- `hiaRedactIfSecretLike(name, value)` masks a value when its field name appears credential-related.

These helpers are a safety net, not a complete allow-list.

## Included files

| File | Purpose |
|---|---|
| `HubitatInternalApiLib.groovy` | `#include`-able read-only endpoint library |
| `InternalEndpointTester.groovy` | Standalone app that checks endpoint availability and general response type without storing response bodies |

## Installing the library

1. In **Developer Tools → Libraries Code**, create a new library.
2. Paste `HubitatInternalApiLib.groovy` and save it.
3. Include it from an app or driver:

```groovy
#include gordonthelander.HubitatInternalApiLib
```

The namespace may be changed before installation if required. If changed, update the `#include` statement accordingly.

## Synchronous usage

Every synchronous fetch returns the same result shape:

```groovy
Map result = hiaFetch(epDeviceFullJson(deviceId))

if (result.ok) {
    Map deviceData = result.data as Map
} else {
    log.warn "Internal endpoint unavailable: ${result.error}"
}
```

Result fields:

```text
ok     boolean success indicator
data   parsed response or raw scalar value
error  exception message when the request failed
```

## Asynchronous usage

```groovy
hiaAsyncFetch(
    epInstalledAppStatus(appId),
    'installedAppStatusCallback',
    [appId: appId],
    20
)

void installedAppStatusCallback(resp, Map callbackData) {
    if (resp != null && !resp.hasError() && resp.status == 200) {
        Map responseData = resp.json instanceof Map ? resp.json as Map : [:]
        // Select and redact only the required fields.
    }
}
```

`hiaAsyncFetch()` dispatches the request but does not implement queue accounting, retry, or missing-callback recovery. A concurrent consumer must wrap dispatch in `try/catch`, account for reservations, and handle a callback that never arrives. The Transactional Bounded-Async Discovery utility in this repository demonstrates that pattern.

Access `resp.errorMessage` only when `resp.hasError()` is true; Hubitat can throw when the getter is read for a successful async response.

## Compatibility probe

Pass the including application's ID:

```groovy
Map compatibility = hiaProbeCompatibility("${app.id}")
if (!compatibility.ok) {
    log.warn compatibility.detail
    return
}
```

The probe distinguishes usable installed-app JSON from an unavailable endpoint or an HTML login response.

## Endpoint catalogue

| Builder | Path | Tier | Expected content |
|---|---|---:|---|
| `epHubDetails()` | `/hub/details/json` | 1 | Hub identity, platform, hardware and location metadata |
| `epHubCpuInfo()` | `/hub/cpuInfo` | 1 | Processor count and load information |
| `epHubFreeMemory()` | `/hub/advanced/freeOSMemory` | 1 | Free OS memory |
| `epHubDatabaseSize()` | `/hub/advanced/databaseSize` | 1 | Database size |
| `epHubInternalTemp()` | `/hub/advanced/internalTempCelsius` | 1 | Internal temperature |
| `epDeviceFullJson(id)` | `/device/fullJson/{id}` | 1 | Device details, state, commands, scheduling and application usage |
| `epDeviceListData()` | `/device/list/data` | 1 | Device inventory |
| `epDeviceDrivers()` | `/device/drivers` | 2 | Driver definitions |
| `epInstalledAppList()` | `/installedapp/list/data` | 1 | Installed-application inventory |
| `epInstalledAppStatus(id)` | `/installedapp/statusJson/{id}` | 1 | Application settings, state, subscriptions, schedules and children |
| `epHub2DevicesList()` | `/hub2/devicesList` | 1 | Hierarchical device inventory, rooms, protocols and tags |
| `epHub2AppsList()` | `/hub2/appsList` | 1 | Complete installed-application hierarchy |
| `epHub2UserAppTypes()` | `/hub2/userAppTypes` | 2 | User App Code definitions |
| `epHub2RoomsList()` | `/hub2/roomsList` | 1 | Rooms, devices and state summaries |
| `epAppCode(id)` | `/app/ajax/code?id={id}` | 2 | User App Code source |
| `epDriverCode(id)` | `/driver/ajax/code?id={id}` | 2 | User driver source |
| `epLibraryCode(id)` | `/library/ajax/code?id={id}` | 2 | User library source |

Tier definitions:

- **Tier 1:** undocumented read-only endpoint with established ecosystem usage.
- **Tier 2:** undocumented, read-only administration-interface implementation endpoint with greater response-shape risk.

## In-process helpers

These helpers do not make HTTP calls:

| Helper | Purpose |
|---|---|
| `hiaFirmwareVersion()` | Hub firmware version |
| `hiaHubLocalIp()` | Hub local IP address |
| `hiaHubUID()` | Hub UID |
| `hiaHubUptime()` | Hub uptime for restart/cache invalidation detection |
| `hiaDeviceControllerType(device)` | Protocol/controller classification when available |
| `hiaDeviceDisabled(device)` | Device disabled flag |
| `hiaDeviceData(device)` | Device data map |
| `hiaDeviceTypeName(device)` | Driver type name |
| `hiaDeviceDriverType(device)` | System/user driver classification |
| `hiaDeviceLastActivity(device)` | Last-activity heuristic |
| `hiaDeviceStatus(device)` | ACTIVE/INACTIVE/UNKNOWN-style heuristic |
| `hiaAppInstallationState(app)` | COMPLETE/INCOMPLETE installation state |

Each device/app helper fails defensively and returns `null` when the property is unavailable.

## Verification status

On 22 August 2026, all 17 endpoint paths were exercised twice on a Hubitat C-8 and returned HTTP 200 with usable response types. Seven device/app/hub properties were observed successfully on a virtual/LAN device. `controllerType` remained unavailable on that device and should be checked on a native Zigbee or Z-Wave device.

The endpoint tester validates raw paths independently. The reusable library wrapper has been syntax-checked but has not yet been fully exercised through `#include` in a live consumer. Treat this as a pre-release utility until that integration test is complete.

## Running the endpoint tester

Installing or running the tester changes a hub and should be done only with authorization.

1. Create an Apps Code entry from `InternalEndpointTester.groovy`.
2. Install one instance.
3. Optionally provide IDs for a device, installed app, App Code record, driver, or library.
4. Optionally select a device for in-process property checks.
5. Press **Run endpoint tests**.
6. Review PASS, CHECK, and FAIL results on the app page.
7. Remove the temporary installed app and Apps Code entry when testing is complete.

The tester records only endpoint name, HTTP status, general response type, and sanitized error text. It does not retain response bodies.

## Known limitations

- Endpoint names and schemas may change without notice.
- Read-only responses can contain credentials and private source code.
- Login security or future firmware may block loopback access.
- The tester serializes async requests and supports only one run at a time.
- `controllerType` has not been confirmed on a native radio device.
- The library wrapper still requires live `#include` integration testing.
