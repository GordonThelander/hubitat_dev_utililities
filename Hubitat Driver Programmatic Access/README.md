# Hubitat Driver Programmatic Access

A practical method for reading Hubitat device and driver information programmatically, including the driver preferences that Maker API and ordinary Hubitat applications do not expose.

This document distinguishes supported Hubitat interfaces from undocumented administration endpoints. It also defines a safe local-gateway design for tools such as MCP servers, Automation Map, diagnostic utilities and AI assistants.

## Why this is necessary

A Hubitat device has several different kinds of information:

```text
Device instance
├── identity and assigned room
├── assigned driver type
├── capabilities and commands
├── current attributes
├── recent events
├── driver preferences
├── driver state variables
├── device data values
├── scheduled jobs
└── driver source, for user drivers only
```

No single supported external API exposes all of these layers.

For example, a virtual switch may report:

```text
switch = off
```

while also having this driver preference:

```text
autoOff = 1 second
```

Those are separate facts. The first is a current attribute. The second is persistent driver configuration.

Maker API exposes the current `switch` attribute but not the `autoOff` preference. An assessment tool that sees only Maker API data cannot determine why the switch automatically returns to `off` after an `on()` command.

## Hubitat terminology

| Term | Meaning | Example |
| --- | --- | --- |
| Device instance | One installed device record with a numeric ID | Alarm OFF Switch, device `1279` |
| Driver type | Code assigned to the instance | Virtual Switch |
| Capability | Standard behavioural contract | Switch, Refresh |
| Command | Callable driver operation | `on()`, `off()`, `refresh()` |
| Attribute | Public event-derived current state | `switch=off` |
| Preference | User-configured driver setting | `autoOff=1` |
| State variable | Driver-private persistent runtime data | Driver-specific counters or flags |
| Device data | Relatively static metadata | model, manufacturer, firmware |
| Event | Historical attribute transition or command record | switch changed to off |
| Scheduled job | Future work scheduled by the driver | automatic off callback |
| User driver | Groovy source installed under Drivers Code | Community/custom driver |
| System driver | Compiled built-in Hubitat driver | Built-in Virtual Switch |

## Where driver preferences are stored

A driver declares preferences in its Groovy metadata:

```groovy
preferences {
    input name: "autoOff",
          type: "enum",
          title: "Enable auto off",
          options: [
              "disabled": "Disabled",
              "1": "1s",
              "2": "2s"
          ]
}
```

Hubitat stores the selected value in its internal database against the individual device instance. Inside that driver instance, the value is available as:

```groovy
settings.autoOff
```

The preference is not:

- a device attribute;
- a state variable;
- a device data value;
- a Maker API field;
- necessarily represented by an event.

An extractor should distinguish four related values:

| Value | Description |
| --- | --- |
| Preference definition | Key, title, input type, options and declared default |
| Saved value | Underlying value persisted for this device instance |
| Display value | Human-readable option label shown by the UI |
| Effective value | Value the driver will use, accounting for defaults and unsaved settings |

Hubitat notes that a `defaultValue` can appear in the UI before it has been persisted. Therefore, a displayed default must not automatically be reported as a saved setting.

## Access methods

### 1. Maker API

Maker API is the preferred external interface for authorised device inventory, current state, events and commands.

Typical paths are:

```text
GET /apps/api/<app-id>/devices
GET /apps/api/<app-id>/devices/all
GET /apps/api/<app-id>/devices/<device-id>
GET /apps/api/<app-id>/devices/<device-id>/events
GET /apps/api/<app-id>/devices/<device-id>/commands
GET /apps/api/<app-id>/devices/<device-id>/<command>/<arguments>
```

Maker API provides:

- authorised devices;
- names, labels and device types;
- capabilities;
- commands;
- current attributes;
- recent events;
- command execution.

Maker API does not provide:

- arbitrary driver preferences;
- driver `state` variables;
- driver scheduled jobs;
- user-driver source;
- complete application dependencies;
- devices not selected in that Maker API instance.

Maker API tokens must stay inside a trusted local service. Do not expose raw token-bearing URLs to an AI model or browser client.

### 2. Hubitat application device objects

A Hubitat application can access selected devices through the documented device object surface:

```groovy
String switchValue = selectedDevice.currentValue("switch")
selectedDevice.on()
selectedDevice.off()
```

An application can update a device preference when it knows the preference name and type:

```groovy
selectedDevice.updateSetting(
    "autoOff",
    [value: "1", type: "enum"]
)
```

However, an arbitrary application cannot directly read another driver instance's `settings` map. This fails conceptually:

```groovy
selectedDevice.settings.autoOff
```

The owning driver can read `settings.autoOff`; an unrelated application cannot. This is why Automation Map cannot obtain every driver preference using only normal device-object access.

Parent applications have additional access to methods on their child devices, but this does not create a universal preference-read interface for arbitrary devices.

### 3. Cooperative custom drivers

If the user driver is under your control, it can expose a sanitised diagnostic contract:

```groovy
Map getDiagnosticConfiguration() {
    return [
        schemaVersion: 1,
        preferences: [
            autoOff: settings.autoOff
        ],
        stateSummary: [
            lastConfigured: state.lastConfigured
        ]
    ]
}
```

Possible exposure mechanisms include:

- a parent app calling a non-private child-driver method;
- a declared custom command;
- a temporary, sanitised diagnostic attribute;
- a local HTTP endpoint owned by the driver or parent app.

Do not expose credentials, lock codes, tokens, Wi-Fi passwords, certificates or private service URLs.

This approach cannot be added to built-in system drivers because their source is compiled and unavailable.

### 4. Authenticated Hubitat administration interface

The Hubitat administration UI can display information omitted from Maker API:

- driver preferences;
- state variables;
- device data;
- scheduled jobs;
- assigned driver type;
- device dependencies;
- event and log views.

The device page is addressed by numeric device ID:

```text
/device/edit/<device-id>
```

Hubitat also exposes undocumented internal endpoints used by its administration UI and community applications. The established read-only device-detail endpoint is:

```text
/device/fullJson/<device-id>
```

The sibling utility in this repository provides a guarded endpoint builder and probe:

```groovy
#include yournamespace.HubitatInternalApiLib

Map result = hiaFetch(epDeviceFullJson(deviceId))

if (result.ok) {
    Map deviceData = result.data as Map
}
```

Replace `yournamespace` with the namespace declared by the installed copy of
`HubitatInternalApiLib.groovy`. Hubitat requires the `#include` namespace and
library name to match the library definition exactly.

See [`../Hubitat Read-Only Internal API Harness/README.md`](../Hubitat%20Read-Only%20Internal%20API%20Harness/README.md).

These endpoints are not public Hubitat APIs. Their paths, response structures and authentication behaviour can change with any platform release. Consumers must capability-test them and fail closed.

### 5. Administration-page DOM fallback

If the current platform does not provide a usable structured preference response, a local authenticated browser can inspect the Preferences tab without saving anything:

```text
open /device/edit/<device-id>
select Preferences
enumerate visible preference containers
read the control ID as the preference key
read title, description, control type and selected value
do not click Save
```

For the tested Alarm OFF Switch, the page exposed:

```html
<div id="autoOff" class="p-dropdown ...">
    <span role="combobox" aria-label="1s">1s</span>
</div>
```

This establishes:

```text
preference key: autoOff
title: Enable auto off
control type: enum/dropdown
display value: 1s
```

DOM extraction is a fallback because CSS structure and UI components are more likely to change than a reusable JSON adapter.

## Reading driver source

### User drivers

User-installed Groovy source is available under Drivers Code. The internal read-only source endpoint used by Hubitat's administration interface is:

```text
/driver/ajax/code?id=<driver-type-id>
```

The read-only harness exposes this as:

```groovy
epDriverCode(driverTypeId)
```

A source reader should return:

- driver type ID;
- name and namespace;
- source revision or hash;
- source in bounded chunks;
- declared capabilities;
- commands and parameters;
- preference definitions;
- visible library imports.

Treat source as sensitive. It may contain hard-coded keys or internal addresses even though well-designed drivers should not do this.

### Built-in drivers

Built-in Hubitat drivers are compiled system code. Their Groovy implementation source is not available from Drivers Code.

For a built-in driver, report only observable evidence:

- system driver name and type ID;
- capabilities;
- commands and parameter metadata;
- attributes;
- visible preference definitions and values;
- state variables and data shown in the administration UI;
- events, logs and scheduled jobs;
- official documentation;
- observed behaviour.

Never claim to have read the source of a built-in driver.

## Recommended local architecture

```text
AI client
   |
   | structured requests only
   v
Local deterministic gateway
   ├── Maker API adapter
   │     inventory, attributes, events, commands
   ├── authenticated admin read adapter
   │     preferences, state, data, jobs, type
   ├── driver source adapter
   │     user-driver source and metadata
   ├── Automation Map adapter
   │     dependencies, device roles and rule flows
   └── policy, cache, redaction and audit log
```

The AI must never receive:

- Maker API tokens or token-bearing URLs;
- Hubitat administrator credentials;
- authenticated session cookies;
- raw internal endpoint URLs containing credentials;
- unredacted preference or source secrets.

## Proposed MCP interface

### `hub_get_device_runtime`

Returns supported runtime information:

```json
{
  "deviceId": 1279,
  "displayName": "Alarm OFF Switch",
  "room": "Virtual",
  "driver": {
    "typeId": 56,
    "name": "Virtual Switch",
    "kind": "system"
  },
  "capabilities": ["Switch", "Refresh"],
  "commands": ["on", "off", "refresh"],
  "attributes": {
    "switch": {
      "value": "off",
      "date": "2026-08-23T10:24:41+0000"
    }
  }
}
```

### `hub_get_device_configuration`

Returns authenticated, read-only configuration:

```json
{
  "deviceId": 1279,
  "driverTypeId": 56,
  "preferences": [
    {
      "name": "autoOff",
      "title": "Enable auto off",
      "type": "enum",
      "savedValue": "1",
      "displayValue": "1s",
      "defaultValue": null,
      "source": "admin-device-page"
    }
  ],
  "stateVariables": [],
  "dataValues": {},
  "scheduledJobs": [],
  "redactions": [],
  "retrievedAt": "2026-08-23T00:00:00Z"
}
```

The response must distinguish:

- no preferences;
- preferences unavailable;
- authentication failure;
- endpoint incompatibility;
- default displayed but not saved;
- value deliberately redacted.

### `hub_get_driver_definition`

```json
{
  "driverTypeId": 56,
  "name": "Virtual Switch",
  "kind": "system",
  "sourceAvailable": false,
  "capabilities": ["Switch", "Refresh"],
  "commands": ["on", "off", "refresh"],
  "preferenceDefinitions": [],
  "limitations": [
    "Built-in driver source is compiled and unavailable"
  ]
}
```

For user drivers, `includeSource=true` should be separately permissioned and response-size limited.

### `hub_list_device_jobs`

Returns the driver-scheduled jobs for one exact device ID. This can confirm that an auto-off callback is actually pending after an `on()` command.

### Keep writes separate

Use different tools and permissions for:

```text
hub_call_device_command
hub_update_device_preferences
hub_change_device_driver
```

A read token must never imply permission to call a command, update preferences or change a driver.

## Preference extraction algorithm

```text
1. Accept one exact numeric device ID.
2. Read baseline device identity and driver type.
3. Request the authenticated structured device detail.
4. Verify that the response belongs to the requested device.
5. Extract preference definitions and stored/effective values.
6. Normalise bool, enum, number, decimal and text types.
7. Preserve enum storage value and display label separately.
8. Redact sensitive names and secret-shaped values.
9. Report unsupported or missing fields explicitly.
10. Attach hub platform, source endpoint class and retrieval time.
11. Cache briefly and provide an explicit refresh option.
```

If structured extraction fails, use read-only DOM inspection. Do not silently return `{}` because that would incorrectly mean that the device has no preferences.

## Secret redaction

Suppress preference and source values whose names or definitions indicate:

```text
password
passwd
secret
token
apiKey
oauth
credential
pin
lockCode
privateKey
certificate
psk
ssid
```

Also apply token-shape and entropy checks because a poorly named field may still contain a credential.

Return the preference name and `[REDACTED]`, not the original value.

## Caching

Different layers require different cache policies:

| Data | Suggested lifetime | Invalidate when |
| --- | --- | --- |
| Device identity, room and type | 5 to 15 minutes | Device update or unknown ID |
| Capabilities and commands | 1 hour | Driver type changes |
| Current attributes | Event-driven or a few seconds | Device event |
| Preferences | 5 to 15 minutes | Preferences saved or explicit refresh |
| Driver source hash | Until code revision changes | Driver saved/imported/updated |
| Automation dependencies | Automation Map scan lifetime | Map rescan |
| Events and scheduled jobs | Live or very short | Every request |

Every cached configuration response should carry `retrievedAt` and `staleAfter`.

## Read and write permissions

Recommended read scopes:

```text
device-state-read
device-config-read
driver-source-read
automation-map-read
```

Recommended write scopes:

```text
device-command-write
device-config-write
driver-code-write
device-driver-change
```

Changing a driver should require an explicit confirmation and recent backup. Updating preferences should require a prior read, type/option validation, an exact-device check, an audit record and a post-write readback.

## Failure model

Return explicit status for:

- device not found;
- device disabled;
- administrator session unavailable;
- Hub Login Security blocking loopback access;
- endpoint changed or returned an unexpected schema;
- device has no preferences;
- displayed default has not been persisted;
- preference value redacted;
- built-in driver source unavailable;
- parser only partially understood the response.

Do not turn any of these into an unqualified empty collection.

## Worked example: Alarm OFF Switch

Observed device:

```text
device ID: 1279
name: Alarm OFF Switch
driver: system Virtual Switch
capabilities: Switch, Refresh
current attribute: switch=off
preference key: autoOff
preference display value: 1s
```

Automation relationship:

```text
Panic ON
  -> waits five minutes
  -> commands Alarm OFF Switch on

Alarm OFF Switch
  -> emits switch=on
  -> triggers Panic OFF
  -> its driver schedules automatic off after one second
```

Maker API and the existing runtime MCP read could prove `switch=off`, but could not prove the `autoOff=1s` configuration. Reading the authenticated Preferences view established that missing fact.

This example demonstrates why automation assessment needs both runtime device data and driver configuration evidence.

## Proof-of-concept plan

### Phase 1: read-only extraction

Implement:

- device runtime reader;
- device configuration reader;
- driver definition/source reader;
- secret redaction;
- source/version tagging;
- cache with explicit refresh.

Test against:

1. system Virtual Switch with auto-off;
2. system driver with no preferences;
3. Zigbee device with configuration preferences and data values;
4. Z-Wave device with configuration parameters;
5. integration-created child device;
6. user driver with readable source;
7. fake secret preference to verify redaction;
8. Hub Login Security enabled and disabled.

### Phase 2: Automation Map evidence

Add optional, minimal configuration evidence to the AI assessment export:

```json
{
  "deviceConfigurationEvidence": [
    {
      "deviceId": "d1279",
      "facts": [
        {
          "key": "autoOff",
          "displayValue": "1s",
          "classification": "timing-safeguard"
        }
      ]
    }
  ]
}
```

Do not export all preferences by default. Export only facts needed as evidence, or require the user to choose a configuration-inclusive export.

### Phase 3: guarded writes

Only after the read layer, redaction and audit logging are proven should the gateway add separately authorised preference updates or driver changes.

## Compatibility warning

The authenticated administration endpoints described here are undocumented Hubitat implementation details. They may change without notice.

Every implementation must:

- probe capability at runtime;
- validate content type and schema;
- reject HTML/login responses when JSON is expected;
- avoid retaining raw responses;
- record the platform build used for validation;
- fail closed after a platform change;
- preserve the supported Maker API path for routine operations.

## References

- [Hubitat Device Detail documentation](https://docs2.hubitat.com/en/user-interface/devices/device-detail)
- [Hubitat Driver Overview](https://docs2.hubitat.com/en/developer/driver/overview)
- [Hubitat Maker API documentation](https://docs2.hubitat.com/en/apps/maker-api)
- [Hubitat discussion confirming that arbitrary apps cannot directly read driver preferences](https://community.hubitat.com/t/get-device-setting-from-an-app/38553)
- [Hubitat Automation Map](https://github.com/GordonThelander/hubitat-automation-map)
- [Hubitat Read-Only Internal API Harness](../Hubitat%20Read-Only%20Internal%20API%20Harness/README.md)
