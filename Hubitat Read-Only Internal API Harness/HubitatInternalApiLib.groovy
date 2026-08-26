/**
 * HubitatInternalApiLib
 *
 * Freestanding, reusable Hubitat Library wrapping undocumented internal hub
 * HTTP endpoints (the ones under /hub, /hub2, /device, /installedapp,
 * /app, /driver, /library that back the admin UI but have no published
 * Developer API). See README.md for compatibility and security guidance.
 *
 * Source: an HPM-ecosystem assessment of undocumented endpoint usage,
 * followed by read-only verification on a Hubitat C-8 hub.
 *
 * Scope: READ-ONLY introspection endpoints only (assessment Tiers 0-2).
 * Deliberately excludes every write/mutation/admin endpoint (code
 * save/update/delete, reboot, radio/network, factory reset - Tiers 3-4).
 * Hubitat provides no compatibility contract for any of this: every call
 * degrades to [ok:false, error:...] on failure rather than throwing, so a
 * consumer can treat a missing endpoint as "not available on this
 * firmware" rather than a crash.
 *
 * Usage (once wired into a real app):
 *   #include yourNamespace.HubitatInternalApiLib
 *   Map result = hiaFetch(epDeviceFullJson(someDeviceId))
 *   if (result.ok) { ... }
 */
library(
    name: 'HubitatInternalApiLib',
    namespace: 'gordonthelander',
    author: 'Gordon Thelander',
    description: 'Read-only wrapper for undocumented Hubitat internal introspection endpoints (hub/device/app/driver/library JSON, health telemetry) with firmware lookup and secret redaction.',
    category: 'Utility',
    documentationLink: 'https://github.com/GordonThelander/hubitat_dev_utililities/tree/main/Hubitat%20Read-Only%20Internal%20API%20Harness'
)

import groovy.transform.Field

@Field static final String HIA_LOOPBACK_BASE = 'http://127.0.0.1:8080'

// ===================================================================================================================
// Stability tiers, per the source assessment's recommended model. Tier 0-2
// only - this library never exposes a Tier 3+ (mutation/admin) endpoint.
//   Tier 0 - supported (published Developer API - out of scope here)
//   Tier 1 - undocumented read-only endpoint, long ecosystem history
//   Tier 2 - undocumented admin-UI implementation endpoint (still read-only)
// ===================================================================================================================
@Field static final Map<String, Integer> HIA_ENDPOINT_TIERS = [
    hubDetails       : 1,
    hubCpuInfo       : 1,
    hubFreeMemory    : 1,
    hubFreeMemoryHistory: 1,
    hubFreeMemoryLast: 2,
    hubDatabaseSize  : 1,
    hubInternalTemp  : 1,
    hubZwaveDetails  : 2,
    hubZigbeeDetails : 2,
    hubMatterDetails : 2,
    hubZigbeeChildRoute: 2,
    hubZigbeeChildRouteJson: 2,
    deviceFullJson   : 1,
    deviceListData   : 1,
    deviceDrivers    : 2,
    installedAppList : 1,
    installedAppStatus: 1,
    hub2DevicesList  : 1,
    hub2AppsList     : 1,
    hub2UserAppTypes : 2,
    hub2UserDeviceTypes: 1,
    hub2RoomsList    : 1,
    hub2HubData      : 1,
    hub2HubMesh      : 2,
    hub2NetworkConfiguration: 2,
    appCode          : 2,
    driverCode       : 2,
    libraryCode      : 2,
]

// ===================================================================================================================
// Endpoint path builders. Each returns a loopback-relative path (no host) -
// callers pass the result straight to hiaFetch()/hiaAsyncFetch(), which
// prefix HIA_LOOPBACK_BASE. Kept separate from the fetch call so a caller
// can log/inspect the path without making the request.
// ===================================================================================================================
String epHubDetails() { '/hub/details/json' }
String epHubCpuInfo() { '/hub/cpuInfo' }
String epHubFreeMemory() { '/hub/advanced/freeOSMemory' }
String epHubFreeMemoryHistory() { '/hub/advanced/freeOSMemoryHistory' }
String epHubFreeMemoryLast() { '/hub/advanced/freeOSMemoryLast' }
String epHubDatabaseSize() { '/hub/advanced/databaseSize' }
String epHubInternalTemp() { '/hub/advanced/internalTempCelsius' }
String epHubZwaveDetails() { '/hub/zwaveDetails/json' }
String epHubZigbeeDetails() { '/hub/zigbeeDetails/json' }
String epHubMatterDetails() { '/hub/matterDetails/json' }
String epHubZigbeeChildRoute() { '/hub/zigbee/getChildAndRouteInfo' }
String epHubZigbeeChildRouteJson() { '/hub/zigbee/getChildAndRouteInfoJson' }

String epDeviceFullJson(String deviceId) { "/device/fullJson/${deviceId}" }
String epDeviceListData() { '/device/list/data' }
String epDeviceDrivers() { '/device/drivers' }

String epInstalledAppList() { '/installedapp/list/data' }
String epInstalledAppStatus(String appId) { "/installedapp/statusJson/${appId}" }

String epHub2DevicesList() { '/hub2/devicesList' }
String epHub2AppsList() { '/hub2/appsList' }
String epHub2UserAppTypes() { '/hub2/userAppTypes' }
String epHub2UserDeviceTypes() { '/hub2/userDeviceTypes' }
String epHub2RoomsList() { '/hub2/roomsList' }
String epHub2HubData() { '/hub2/hubData' }
String epHub2HubMesh() { '/hub2/hubMeshJson' }
String epHub2NetworkConfiguration() { '/hub2/networkConfiguration' }

String epAppCode(String appCodeId) { "/app/ajax/code?id=${appCodeId}" }
String epDriverCode(String driverCodeId) { "/driver/ajax/code?id=${driverCodeId}" }
String epLibraryCode(String libraryCodeId) { "/library/ajax/code?id=${libraryCodeId}" }

// ===================================================================================================================
// Call wrappers. Synchronous calls use one normalized [ok, data, error]
// result shape so consumers do not need endpoint-specific exception logic.
// ===================================================================================================================

/**
 * Synchronous GET against a loopback path. path may be an absolute path
 * ("/hub/details/json") or a full URL - only strings not already starting
 * with "http" get HIA_LOOPBACK_BASE prefixed.
 */
Map hiaFetch(String path, int timeoutSec = 10, Map extraOpts = [:]) {
    String uri = path.startsWith('http') ? path : "${HIA_LOOPBACK_BASE}${path}"
    Map out = [ok: false, data: null, error: null]
    try {
        httpGet(extraOpts + [uri: uri, timeout: timeoutSec]) { resp ->
            out.data = resp.data
            out.ok = true
        }
    } catch (Exception ex) {
        out.error = "${ex.message}"
    }
    return out
}

/**
 * Asynchronous GET against a loopback path, dispatched via Hubitat's
 * asynchttpGet. callbackMethod must be a method defined on the including
 * app/driver (Hubitat's async callbacks are resolved by name against the
 * caller, not against this library). callbackData is passed through
 * unchanged as the callback's second argument.
 */
void hiaAsyncFetch(String path, String callbackMethod, Map callbackData = [:], int timeoutSec = 10) {
    String uri = path.startsWith('http') ? path : "${HIA_LOOPBACK_BASE}${path}"
    asynchttpGet(callbackMethod, [uri: uri, contentType: 'application/json', timeout: timeoutSec], callbackData)
}

/**
 * Reachability probe. referenceAppId should be the including app's own
 * app.id (or any known-installed app id) - distinguishes "endpoint
 * unreachable" from "endpoint reachable but answered with a login page"
 * (Hub Login Security).
 */
Map hiaProbeCompatibility(String referenceAppId) {
    Map out = [ok: false, detail: '']
    Map result = hiaFetch(epInstalledAppStatus(referenceAppId), 10)
    if (!result.ok) {
        out.detail = "Could not reach the hub's internal app endpoint (${result.error}). This Hubitat version may not expose /installedapp/statusJson."
    } else if (result.data instanceof Map && (result.data as Map).installedApp) {
        out.ok = true
        out.detail = 'Hub internal endpoints reachable.'
    } else {
        out.detail = 'The hub answered, but not with app JSON. If Hub Login Security is enabled, internal endpoints cannot be read.'
    }
    return out
}

// ===================================================================================================================
// Hub identity/firmware accessors. Documented undocumented-in-practice per
// the source assessment (location.hub is a documented property, but these
// individual fields have no current developer reference) - useful for
// compatibility diagnostics, which no consuming app is required to build
// but every consuming app benefits from recording.
// ===================================================================================================================
String hiaFirmwareVersion() { location?.hub?.firmwareVersionString as String }
String hiaHubLocalIp() { location?.hub?.localIP as String }
String hiaHubUID() { getHubUID() as String }

/** Restart detection: a drop in uptime since the last reading means invalidate cached introspection/topology data. */
Long hiaHubUptime() {
    try { return location?.hub?.uptime as Long } catch (Exception ignored) { return null }
}

// ===================================================================================================================
// In-process device/app metadata - no HTTP call. Documentation-gap
// DeviceWrapper/App properties and methods per a second-pass community scan
// (2026-08-22), separate from the HTTP endpoints above and from the
// official Developer Docs. Unlike an undefined top-level function call,
// invoking an unsupported property/method on these typed wrapper objects
// throws rather than silently returning null, so every accessor here is
// wrapped defensively and returns null on failure instead of crashing the
// caller - the same "capability-test, don't assume" posture the source
// assessment recommends for the HTTP endpoints.
// ===================================================================================================================

String hiaDeviceControllerType(device) {
    try { return device?.controllerType as String } catch (Exception ignored) { return null }
}

Boolean hiaDeviceDisabled(device) {
    try { return device?.disabled as Boolean } catch (Exception ignored) { return null }
}

/** Device Data map (model, manufacturer, etc.) without a /device/fullJson/{id} round trip. */
Map hiaDeviceData(device) {
    try { return device?.getData() as Map } catch (Exception ignored) { return null }
}

String hiaDeviceTypeName(device) {
    try { return device?.getTypeName() as String } catch (Exception ignored) { return null }
}

String hiaDeviceDriverType(device) {
    try { return device?.driverType as String } catch (Exception ignored) { return null }
}

/**
 * Staleness heuristic, not proof of reachability - per the source scan,
 * this has at times reflected only the latest event, while the Device
 * page's Last Activity can be advanced by lower-level traffic that never
 * generates an event.
 */
Object hiaDeviceLastActivity(device) {
    try { return device?.getLastActivity() } catch (Exception ignored) { return null }
}

/** ACTIVE/INACTIVE/UNKNOWN-style heuristic - not a definitive health signal. */
String hiaDeviceStatus(device) {
    try { return device?.status as String } catch (Exception ignored) { return null }
}

/** COMPLETE / INCOMPLETE install lifecycle state for an app instance. */
String hiaAppInstallationState(targetApp) {
    try { return targetApp?.getInstallationState() as String } catch (Exception ignored) { return null }
}

// ===================================================================================================================
// Secret redaction. Undocumented endpoints - especially
// /installedapp/statusJson/{id} - can return other apps' settings verbatim,
// including OAuth tokens, API keys and passwords. Neither helper below
// tries to be a full allow-list (the set of fields worth keeping is
// inherently caller-specific); they scrub the common shapes so a caller
// that surfaces raw values by accident doesn't leak a live credential.
// ===================================================================================================================

/** Scrubs common secret-bearing query-param shapes out of a URL string. */
String hiaRedactUrl(String url) {
    if (!url) return url
    String out = url
    out = out.replaceAll(/(?i)(access_token|api_key|apikey|token|password|secret|key)=[^&\s]*/, '$1=REDACTED')
    out = out.replaceAll(/(?i)(bearer\s+)[A-Za-z0-9\-_.]+/, '$1REDACTED')
    return out
}

/**
 * Given a setting/state name and its raw value, returns 'REDACTED' if the
 * name looks secret-bearing (token/password/secret/key/apikey/auth),
 * otherwise returns the value unchanged. Intended for a caller iterating
 * appSettings/appState entries from /installedapp/statusJson and deciding
 * per-field whether to keep, redact, or drop.
 */
String hiaRedactIfSecretLike(String settingName, String value) {
    if (settingName ==~ /(?i).*(token|password|secret|apikey|api_key|\bkey\b|auth).*/) {
        return 'REDACTED'
    }
    return value
}
