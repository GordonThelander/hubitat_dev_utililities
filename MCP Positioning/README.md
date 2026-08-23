# MCP Positioning

## A positioning paper for MCP in a Hubitat-centred residential automation architecture

**Status:** architectural position and evolution roadmap  
**Basis:** applications installed on the reviewed Hubitat C-8, their exposed tool architecture, Automation Map, Hubitat platform behaviour and the Model Context Protocol specification  
**Date:** 23 August 2026

## Executive position

Model Context Protocol should be positioned as the **governed interoperability, reasoning and engineering plane above Hubitat**, not as a replacement for Hubitat's local automation engine and not as the mandatory path for every real-time device action.

Hubitat should remain responsible for:

- deterministic event processing;
- Zigbee, Z-Wave, Matter and LAN device integration;
- sub-second rules and device execution;
- operation during internet or AI outages;
- safety-critical automation that must not depend on a language model.

MCP should provide:

- a standard way for authorised AI clients to discover and call structured capabilities;
- read-only household context and dependency analysis;
- constrained execution of ordinary user-requested actions;
- diagnostics, rule inspection and engineering workflows;
- policy enforcement, auditability and separation of privileges;
- a path for local and cloud AI systems to use the same home interface.

The recommended target is:

```text
                       Advanced cloud AI
                    planning and recommendations
                              |
                              | MCP, scoped context
                              v
Voice/chat ----------> Local AI and policy gateway
                              |
             +----------------+----------------+
             |                |                |
             v                v                v
      Automation Map     Control adapter   Engineering adapter
       read-only graph    small allowlist    privileged, gated
             |                |                |
             +----------------+----------------+
                              |
                           Hubitat
                  rules, devices and execution
```

MCP is therefore the protocol boundary. The local policy gateway is the trust boundary. Hubitat is the execution authority. Automation Map is the structural-context authority.

## 1. Current installed estate

The reviewed hub currently has two distinct MCP implementations plus supporting applications.

### 1.1 Hubitat AI Connector Integration

Installed instance:

```text
Name: AI Connector Integration
Type: AI (MCP) Connector Integration
Implementation: Hubitat built-in system application
Status: running
Default tool approval mode: approve
```

The connected client currently exposes 49 direct tools from this server. They cover:

- device and state lookup;
- device, room and capability search;
- lights, fans, covers, locks and thermostats;
- scenes and buttons;
- modes and application enable/disable;
- events and hub diagnostics;
- Visual Rules Builder 2 reading, validation, creation and update;
- high-level home-assistant-style operations such as room on/off.

Representative tools include:

```text
hubitat_search_devices
hubitat_get_context_summary
hubitat_turn_on
hubitat_turn_off
hubitat_room_turn_on
hubitat_light_set
hubitat_activate_scene
hubitat_lock
hubitat_unlock
hubitat_vrb2_validate_rule
hubitat_vrb2_create_rule
```

This connector is device- and interaction-oriented. Its high-level tools are suitable for ordinary home commands because the tool names and parameters closely reflect user intent.

### 1.2 MCP Rule Server

Installed instance:

```text
Name: MCP Rule Server
Implementation: user application by kingpanther13
Installed package version: 4.0.2
Transport: Streamable HTTP
Authentication: Hubitat OAuth bearer token
```

The upstream project describes 117 tools in its catalogue. Gateway consolidation reduces the visible surface to 36 top-level tools in the current connected client, with most operations accessed through domain gateways.

Current major gateways include:

```text
hub_read_devices
hub_read_rules
hub_read_apps_code
hub_read_diagnostics
hub_manage_devices
hub_manage_rule_machine
hub_manage_native_rules_and_apps
hub_manage_code
hub_manage_radio
hub_manage_system_settings
hub_manage_destructive_ops
```

This is not merely a device-control server. It is a broad Hubitat engineering and administration interface capable of reading and, when permitted, changing devices, rules, code, variables, rooms, files, dashboards, radios and system settings.

Current configuration observed during this review:

| Setting | Current value | Positioning significance |
| --- | --- | --- |
| Read tools | Enabled | Broad inspection is available |
| Write tools | Enabled | State-changing and administrative tools are available |
| Device allowlist bypass | Enabled | Device selection does not constrain direct device reach by ID |
| Selected devices | None | With bypass enabled, effective scope is still broad |
| Best-practice acknowledgement for writes | Enabled | Useful procedural guard before writes |
| Developer mode | Disabled | MCP self-administration is restricted |
| Legacy custom rule engine | Disabled | Native Hubitat rule engines are preferred |
| Gateway consolidation | Enabled | Reduces tool-list size and model context pressure |
| MCP log level | Error | Limits routine diagnostic verbosity |
| Loop guard | 30 executions in 60 seconds | Protects the legacy rule execution path |

The device allowlist bypass and global Write master mean this server should currently be treated as an **engineering administrator**, not a routine household-control connector.

### 1.3 Automation Map

Installed applications include production and development Automation Map instances.

Automation Map provides:

- a read-only graph of apps, devices, external systems and Hub Variables;
- device roles such as trigger, constraint, monitor and action;
- decoded Rule Machine flows where supported;
- cross-rule relationships;
- contested-device and broken-reference insights;
- an AI-friendly static export.

Automation Map does not command devices or modify applications. That boundary is strategically valuable and should be preserved.

### 1.4 Supporting diagnostic applications

The installed estate also includes:

- Hub Diagnostics;
- a Read-Only Internal Endpoint Tester;
- Maker API instances for separate consumers;
- Rule Machine and Visual Rules Builder;
- Hubitat Package Manager;
- integrations for Hue, LIFX, presence, weather, garage door, notifications and other subsystems.

These applications establish that the hub already has a mature local execution and diagnostic environment. MCP should coordinate access to it, not duplicate every subsystem.

## 2. What MCP is, and is not

MCP is a standard client-server protocol for exposing:

- **tools**, callable operations;
- **resources**, contextual data identified by URI;
- **prompts**, reusable interaction templates;
- capability negotiation and lifecycle metadata.

The MCP host is the user-facing application. It manages one or more MCP clients, permissions and user consent. Each MCP client connects to an MCP server. The server exposes capabilities but does not decide the user's overall intent.

MCP is not:

- an AI model;
- a rule engine;
- a real-time fieldbus;
- a device protocol;
- a safety policy by itself;
- proof that a tool is trustworthy;
- a substitute for server-side authorisation;
- a guarantee of low latency.

Tool descriptions and annotations help a model choose operations, but MCP's own specification warns clients to treat tool annotations as untrusted unless the server itself is trusted.

## 3. Current architecture

### 3.1 Logical view

```text
Codex / Claude / another MCP host
        |
        +----------------------------+
        |                            |
        v                            v
Hubitat AI Connector           MCP Rule Server
built-in system app            user app, v4.0.2
49 direct tools                36 visible gateway/core tools
        |                            |
        |                     +------+------+
        |                     |             |
        |                     v             v
        |                Public device   Internal admin
        |                objects/APIs     endpoints/helpers
        |                     |             |
        +---------------------+-------------+
                              |
                           Hubitat
                 devices, apps, rules, state
```

### 3.2 Transport

Both connectors ultimately use HTTP-accessible Hubitat application endpoints. The community MCP server uses MCP Streamable HTTP and Hubitat OAuth authentication. It can expose a local LAN endpoint and a cloud-relayed endpoint.

Local transport offers:

- no dependency on an internet route between client and hub;
- lower network latency;
- reduced external exposure;
- continued operation during WAN failure, provided the client is local.

Cloud-relayed transport offers:

- remote reachability;
- simpler connection for cloud-hosted AI;
- greater token, privacy and availability exposure;
- dependence on external infrastructure.

### 3.3 Tool discovery

The built-in connector exposes direct, semantically narrow tools. The community server uses progressive disclosure through category gateways.

Direct tools are easier for models to select but can create a large tool catalogue. Gateways reduce `tools/list` size but require an additional dispatch step and can increase latency or selection complexity.

Neither pattern is universally superior. The appropriate choice depends on client capability and use case:

| Client/use case | Preferred exposure |
| --- | --- |
| Fast household commands | Small direct tool set |
| General AI assistant | High-level tools plus search |
| Engineering agent | Gateway-based progressive disclosure |
| Deterministic service | Fixed structured API, no model discovery |

### 3.4 Direct contrast: Hubitat connector versus community server

The two installed MCP servers solve different problems despite their overlapping device tools.

| Dimension | Hubitat AI Connector Integration | Community MCP Rule Server |
| --- | --- | --- |
| Ownership | Built into the Hubitat platform | Community user application by kingpanther13 |
| Installed instance | AI Connector Integration, app `3057` | MCP Rule Server, app `3060` |
| Maintenance | Hubitat platform release lifecycle | GitHub/HPM project release lifecycle |
| Implementation visibility | Compiled system application, source unavailable | User Groovy application and libraries, source inspectable |
| Primary orientation | AI-friendly device and home interaction | Broad hub engineering, administration and rule tooling |
| Visible client surface | 49 direct tools in the reviewed client | 36 top-level core/gateway tools representing a 117-tool catalogue |
| Tool organisation | Mostly direct, task-specific tools | Core tools plus domain gateways and tool search |
| Typical operation | `hubitat_turn_off`, `hubitat_light_set` | `hub_manage_devices` dispatching a named sub-tool |
| Device control | High-level lights, rooms, fans, covers, locks, thermostats and scenes | Generic and high-level device operations plus configuration and administration |
| Context | Live device/room/mode/event context | Live context plus source, diagnostics, variables, files, rules, radios and system administration |
| Rules | Visual Rules Builder 2 read/validate/create/update and action execution | Native Rule Machine, Visual Rules Builder, classic apps and optional legacy MCP rule engine |
| Driver preference access | Current device tool does not expose arbitrary preferences | Architecture can reach internal administration surfaces, but a dedicated normalised preference-read tool is still needed |
| Source-code access | Not exposed in the reviewed built-in surface | Reads and writes user app/driver/library source through engineering gateways |
| Diagnostics | Basic hub diagnostics and event reads | Performance, logs, jobs, memory, radios, backups and deeper internal diagnostics |
| Write scope | Device/home actions and selected app/rule operations | Potentially hub-wide, including code, files, rules, devices, radio and system settings |
| Permission model | Client approval plus server-specific controls | Read/Write masters, per-tool overrides, device allowlist/bypass, best-practice gate, confirmation and backup gates |
| Current device scope | Determined by the built-in connector configuration/platform | Effectively broad because device allowlist bypass is currently enabled |
| Developer mode | Not applicable as a visible setting | Available but currently disabled |
| Custom rule engine | No | Included as a legacy option, currently disabled |
| Transport role | Native Hubitat MCP connection | Streamable HTTP from an OAuth-enabled user app |
| Local/cloud options | Designed for supported local-client integration | Explicit local endpoint and optional Hubitat cloud endpoint |
| Compatibility risk | Lower, because Hubitat owns both platform and connector | Higher, because it relies on community code and undocumented internal interfaces |
| Innovation speed | Tied to Hubitat product releases | Rapid community release cadence and broad experimentation |
| Best fit | Routine control and ordinary household interaction | Diagnostics, development, rule engineering and advanced administration |

### 3.5 Hubitat AI Connector strengths

The built-in connector's major strengths are:

- first-party ownership;
- direct knowledge of the current platform;
- concise, semantically meaningful home-control tools;
- lower model-selection complexity for ordinary requests;
- high-level room and device operations;
- safer default positioning than a general administration surface;
- native evolution alongside Hubitat firmware and Visual Rules Builder.

Examples of good built-in connector requests:

```text
Turn off the study lights
Set the bedroom fan to low
What devices are on in the kitchen?
Activate a named scene
Validate this proposed Visual Rules Builder rule
```

Its limitations in the reviewed implementation include:

- no arbitrary driver preference view in `get_device`;
- less source-code and internal-state visibility;
- less comprehensive Rule Machine administration;
- fewer deep system-maintenance tools;
- compiled implementation, so behaviour cannot be independently inspected or patched;
- dependence on Hubitat's release priorities for feature expansion.

### 3.6 Community MCP Rule Server strengths

The community server's major strengths are:

- unusually broad Hubitat coverage;
- inspectable source and rapid development;
- native Rule Machine and classic-app interoperability;
- source, package, file and diagnostic access;
- read/write separation and per-tool override architecture;
- progressive tool disclosure through gateways;
- best-practice acknowledgement for write operations;
- backup and confirmation gates for destructive actions;
- a local Streamable HTTP endpoint suitable for many MCP clients;
- ability to prototype capabilities before Hubitat provides them natively.

Examples of good community-server requests:

```text
Read this Rule Machine configuration
List scheduled jobs and performance statistics
Inspect a user driver in bounded chunks
Validate HPM component presence
Create a backup before an approved engineering change
Read device configuration through a guarded internal adapter
```

Its limitations and risks include:

- very large potential blast radius;
- dependence on undocumented Hubitat interfaces;
- more complex tool discovery and dispatch;
- greater model-context and latency cost;
- community maintenance and regression risk;
- potential source drift from the installed HPM package;
- dangerous outcomes if the allowlist, Write master or administrative gateways are too broadly enabled;
- overlap with first-party tools, which can confuse clients unless server roles are explicit.

### 3.7 They are complementary, not interchangeable

The built-in server should be treated as the **productised interaction interface**. The community server should be treated as the **advanced engineering interface**.

```text
Hubitat AI Connector
    "operate and observe the home"

Community MCP Rule Server
    "inspect, diagnose, author and administer the hub"
```

Using the community server for every light command is possible but unnecessarily exposes a broad engineering surface. Using only the built-in connector for deep Rule Machine, source and driver analysis leaves important evidence unavailable.

The recommended client registration is therefore:

```json
{
  "mcpServers": {
    "hubitat-control": {
      "role": "routine household interaction",
      "implementation": "Hubitat AI Connector"
    },
    "hubitat-engineering": {
      "role": "read-only diagnostics by default; approved maintenance when enabled",
      "implementation": "Community MCP Rule Server"
    }
  }
}
```

The illustrative `role` and `implementation` fields above describe the intended local configuration policy. They are not asserted as standard MCP client configuration fields.

### 3.8 Selection policy

The host or local gateway should route requests by intent:

| Request | Preferred server | Reason |
| --- | --- | --- |
| Turn a light on/off | Hubitat connector | Direct high-level tool and smaller privilege surface |
| Control a room | Hubitat connector | Native room-oriented operations |
| Read current state | Hubitat connector | Direct live context |
| Activate scene | Hubitat connector | Explicit scene tool |
| Inspect Rule Machine internals | Community server | Deep rule/application configuration access |
| Read driver preferences | Community server after adding the configuration reader | Requires administration-side inspection |
| Read user driver source | Community server | Source tools and chunking |
| Diagnose hub performance | Community server | Detailed performance, logs and jobs |
| Create/update VRB rule | Hubitat connector first | First-party supported authoring surface |
| Create/edit complex Rule Machine rule | Community server, engineering mode | Broader native Rule Machine authoring |
| Firmware, radio or network work | Community server, exceptional engineering session | Administrative surface with backup/confirmation controls |

If both servers can satisfy a request, choose the least-privileged server that can complete it.

### 3.9 Execution

Hubitat remains the final executor. MCP tools call Hubitat commands, APIs, application methods or internal administrative surfaces. An MCP response stating that a command was accepted does not prove:

```text
command issued
    = state changed
    = event emitted
    = physical outcome achieved
```

These are separate stages. Verification policy must depend on risk.

## 4. Architectural position by use case

### 4.1 Routine device control

Examples:

```text
Turn off Gordon Study lights
Set the lounge to 30 percent
Activate Evening scene
```

Position:

- resolve aliases and rooms locally;
- use cached stable device IDs;
- send parallel structured commands;
- acknowledge immediately for ordinary reversible actions;
- verify asynchronously unless the user requests confirmation;
- avoid general planning and broad tool discovery.

MCP can carry the command, but a cloud LLM should not be required in the critical path.

The observed study-light test demonstrated this distinction:

- cached IDs and parallel fire-and-forget execution completed in approximately 0.6 seconds;
- cloud-model interpretation and synchronous verification produced substantially greater perceived latency.

### 4.2 Personalised interaction

Examples:

```text
Make the study comfortable for evening work
Warn me if I leave with a door open
Why did the perimeter alert run?
```

Position:

- local profile and alias memory;
- Automation Map dependency context;
- live device state through MCP;
- advanced model for ambiguity, trade-offs and explanation;
- structured execution plan checked by a deterministic gateway.

### 4.3 Automation analysis

Position:

- Automation Map supplies static graph and decoded flow evidence;
- read-only MCP tools supply live rule configuration, device preferences, jobs, logs and events;
- AI separates structural configuration from runtime causality;
- recommendations require evidence and user intent;
- no configuration change is implied by analysis.

### 4.4 Engineering and maintenance

Examples:

```text
Inspect a Rule Machine rule
Read a user driver
Check HPM drift
Diagnose hub memory
Create a tested Visual Rule Builder rule
```

Position:

- use the community MCP server's gateway architecture;
- require an explicit engineering session;
- enable only required write domains;
- capture backup/revision before material writes;
- log every call and result;
- require post-write readback;
- never reuse the routine-control credential.

### 4.5 Safety-sensitive control

Targets include:

- locks;
- garage doors;
- alarms and sirens;
- HSM/security state;
- heaters and thermostats;
- valves and irrigation;
- network, radio, firmware and system configuration.

Position:

- blocked by default from conversational control;
- separately allowlisted by exact device and command;
- confirmation before action where consequences are material;
- synchronous post-command verification;
- timeout, rollback or safe-state handling where available;
- never delegated solely to unconstrained model judgement.

## 5. Benefits

### 5.1 Standardised AI integration

MCP decouples the AI client from Hubitat-specific HTTP calls. Claude, Codex, a local model or a future assistant can call the same structured capabilities.

### 5.2 Progressive capability discovery

Clients can discover tools and resources at runtime. The community gateway pattern keeps a broad catalogue usable even when a client cannot handle hundreds of top-level tools efficiently.

### 5.3 Local execution and resilience

A server running on Hubitat can execute locally without a separate always-on computer. A local MCP client can continue operating during an internet outage.

### 5.4 Rich engineering reach

MCP can unify access that would otherwise require many different Hubitat pages:

- devices and events;
- Rule Machine and Visual Rules;
- variables and modes;
- source code and packages;
- diagnostics and radios;
- files, rooms and dashboards.

### 5.5 Separation between language and implementation

The model emits a structured call such as:

```json
{
  "deviceId": 2479,
  "command": "off"
}
```

It does not need the Maker API URL, token or command syntax.

### 5.6 Auditability

MCP calls have explicit tool names and arguments. A gateway can record:

- client identity;
- tool and arguments;
- policy decision;
- target device/app IDs;
- result and verification;
- timestamps and correlation IDs.

### 5.7 Context composition

MCP resources can expose Automation Map summaries, device dependencies and rule explanations without turning them into executable tools.

### 5.8 Controlled evolution

Capabilities can be versioned and added behind new tools without forcing every AI client to understand raw Hubitat internals.

## 6. Risks

### 6.1 Privilege concentration

The community server combines device control with administration, source editing, rules, radios and system settings. A single broad credential can therefore have household-wide impact.

The current allowlist bypass materially increases that reach. It should be viewed as an engineering convenience that weakens normal device scoping.

### 6.2 Credential exposure

Hubitat OAuth tokens may appear in endpoint URLs. Risks include:

- chat transcripts;
- screenshots;
- browser history;
- client configuration files;
- logs and diagnostics;
- copied examples;
- cloud-model prompts.

Prefer bearer headers, secret stores and redacted diagnostics. Rotate any token that may have been disclosed.

### 6.3 Multiple-server ambiguity

Two servers expose overlapping concepts with different names, schemas and safeguards. A model may:

- select an unnecessarily powerful server;
- search twice;
- misunderstand whether a tool is read-only;
- use device names inconsistently;
- incur extra latency;
- receive different results from different caches.

Servers should have explicit roles and names, for example:

```text
hubitat-control
hubitat-engineering
automation-map-context
```

### 6.4 Language-model nondeterminism

A model can misunderstand room membership, names, pronouns or timing. The earlier interpretation of "study lights" showed why room assignment and user aliases must be distinct and why stable IDs should be cached behind a deterministic resolver.

### 6.5 Tool-description trust

Tool metadata is part of the server trust boundary. A compromised or poorly designed server can misdescribe side effects. Clients must enforce policy independently of descriptions.

### 6.6 Prompt injection through household data

Device labels, driver source, files, logs, notifications and app notes are data, not instructions. A malicious or accidental string could attempt to influence an AI agent.

The client must never treat hub content as authorisation.

### 6.7 Undocumented Hubitat internals

The community server's administrative reach depends partly on internal endpoints and implementation details. Platform updates can change response shapes or authentication behaviour.

Capability probing, schema validation and fail-closed behaviour are mandatory.

### 6.8 Hub resource pressure

Large responses, repeated scans, source reads, log queries and excessive tool traffic consume memory, database, CPU and network resources on the automation hub.

The hub should not become the primary AI data-processing machine.

### 6.9 Latency

MCP transport itself can be fast, but perceived latency includes:

```text
model scheduling
intent interpretation
tool discovery
policy checks
MCP round trips
device execution
verification
response generation
```

Caching device IDs only solves discovery, not cloud-model latency.

### 6.10 False assurance from accepted commands

A successful tool response may indicate only that Hubitat accepted a command. Sensitive actions require observed state confirmation and, where relevant, physical-sensor confirmation.

### 6.11 Version and schema drift

The installed HPM package can identify component presence but may not prove that source matches upstream because HPM does not retain per-component source hashes in this environment.

Every server and export should report its version, schema and source revision/hash where possible.

### 6.12 Cloud privacy

Household graphs reveal rooms, occupancy logic, access points, security devices and routines. Sending full Automation Map exports or broad live context to a cloud model requires an explicit privacy decision.

## 7. Recommended target architecture

### 7.1 Three planes

```text
Execution plane
  Hubitat rules, devices, scenes, modes
  Fast, local and deterministic

Context plane
  Automation Map, live state, preferences, events
  Read-only by default

Reasoning and engineering plane
  Local AI, advanced cloud AI, Codex, Claude Code
  Recommendations and controlled maintenance
```

### 7.2 Local policy gateway

The local mini-PC should eventually host a policy gateway that:

- owns MCP client credentials;
- maintains the device/room/alias registry;
- caches ordinary device targets;
- validates structured commands;
- applies device and command allowlists;
- blocks sensitive categories by default;
- rate-limits repeated operations;
- suppresses duplicate commands;
- controls verification requirements;
- records an append-only audit log;
- provides a physical/software kill switch;
- selects the appropriate MCP server.

### 7.3 Client profiles

Do not use one MCP credential for every purpose.

#### Observe

```text
Purpose: explanation, status, dependency analysis
Access: broad reads, no writes
Servers: Automation Map context, read-only Hubitat tools
Cloud AI: permitted only for explicitly selected/redacted context
```

#### Recommend

```text
Purpose: propose automation and maintenance changes
Access: Observe plus validation and dry-run tools
Writes: none
Output: structured proposal with impact/dependency evidence
```

#### Control

```text
Purpose: routine user-requested device actions
Access: small device and command allowlist
Default: lights, scenes and ordinary media actions
Sensitive: blocked or confirmation-gated
Execution: local fast path where possible
```

#### Engineer

```text
Purpose: code, rule and hub maintenance
Access: time-bounded privileged session
Requirements: backup, revision checks, audit, verification
Default state: disabled
```

### 7.4 Server roles

Recommended positioning of current servers:

| Server | Role | Default exposure |
| --- | --- | --- |
| Hubitat AI Connector | Routine control and live state | Constrained devices/actions |
| MCP Rule Server | Engineering, diagnostics and advanced authoring | Read-only unless engineering session is active |
| Automation Map MCP/resource adapter | Dependency and configuration context | Read-only |

## 8. Automation Map's MCP role

Automation Map should not absorb device-control tools. Its value is trustworthy read-only context.

Recommended resources:

```text
hubitat://automation-map/summary
hubitat://automation-map/apps/<app-id>
hubitat://automation-map/devices/<device-id>
hubitat://automation-map/rules/<app-id>/flow
hubitat://automation-map/dependencies/<node-id>
hubitat://automation-map/assessment
```

Recommended read tools:

```text
automation_map_lookup
automation_map_get_neighbors
automation_map_get_dependency_path
automation_map_explain_rule
automation_map_list_assessment_candidates
```

The graph can warn before a command changes:

- a virtual switch used as a coordination signal;
- Location Mode;
- HSM/security state;
- a scene/group activator;
- a device controlled by several rules;
- a Hub Variable with multiple readers;
- a device whose temporary state is captured/restored by another rule.

Warnings should describe dependency consequences without blocking ordinary control unless the policy class requires it.

## 9. Driver and preference access through MCP

The current device tools expose runtime state but not every driver preference. The target engineering server should add:

```text
hub_get_device_configuration
hub_get_driver_definition
hub_list_device_jobs
```

Example:

```json
{
  "deviceId": 1279,
  "preferences": [
    {
      "name": "autoOff",
      "type": "enum",
      "savedValue": "1",
      "displayValue": "1s"
    }
  ]
}
```

This belongs in the engineering/read context, not the routine control tool response. See [Hubitat Driver Programmatic Access](../Hubitat%20Driver%20Programmatic%20Access/README.md).

## 10. Fast-path architecture

Routine instructions should avoid a full advanced-agent loop.

```text
"Turn off Gordon's study lights"
        |
        v
Local intent classifier
        |
        v
Cached alias -> [2478, 2479]
        |
        v
Policy: ordinary reversible lighting action
        |
        v
Parallel structured off commands
        |
        +--> immediate acknowledgement
        +--> optional asynchronous verification
```

Escalate to an advanced model only when:

- the target is ambiguous;
- the request needs planning;
- dependencies materially affect the decision;
- the command is sensitive;
- the user asks for explanation or recommendation;
- the local classifier cannot meet a confidence threshold.

## 11. Command policy

### Ordinary reversible actions

Examples: lights, ordinary scenes, fan on/off, media volume.

Policy:

- fire-and-forget permitted;
- cached IDs;
- parallel execution;
- duplicate suppression;
- asynchronous verification optional;
- clear acknowledgement of what was targeted.

### Material but reversible actions

Examples: thermostat setpoint, whole-house mode, disabling an app.

Policy:

- dependency/context check;
- synchronous state read before action;
- confirmation when intent is ambiguous;
- post-action verification;
- audit record.

### Sensitive actions

Examples: unlock, open garage, disarm security, activate siren, enable heater, change radio/network.

Policy:

- blocked by default;
- explicit allowlist;
- confirmation at action time;
- rate and time-window limits;
- post-command verification;
- no cloud-only execution path;
- kill switch overrides all permits.

### Destructive administration

Examples: delete app/device/rule, change source, update firmware, reset radio.

Policy:

- engineering profile only;
- recent backup;
- exact target and revision verification;
- explicit confirmation;
- rollback plan;
- durable audit log.

## 12. Audit model

Every write-capable MCP call should produce:

```json
{
  "correlationId": "...",
  "time": "...",
  "client": "local-control",
  "user": "Gordon",
  "mode": "Control",
  "server": "hubitat-control",
  "tool": "hubitat_turn_off",
  "targets": [2478, 2479],
  "policyDecision": "allow",
  "confirmation": "not-required",
  "requestHash": "...",
  "result": "accepted",
  "verification": "asynchronous"
}
```

Logs should exclude tokens and sensitive parameter values. Store them outside the hub where possible to avoid hub database growth and preserve evidence across hub failure.

## 13. Immediate recommendations

These are positioning recommendations, not changes made by this review.

### 13.1 Restore device scoping

Turn off device allowlist bypass on the community MCP server unless full-hub reach is deliberately required for a time-bounded engineering session.

### 13.2 Separate control and engineering credentials

Use one narrowly scoped connector for routine device control and a different credential/profile for administrative work.

### 13.3 Default the engineering server to read-only

Keep the Write master disabled outside an explicit maintenance session. Enabling all writes continuously provides little benefit for ordinary chat.

### 13.4 Prefer local endpoints

Use local Streamable HTTP for local clients. Enable cloud reach only where there is a defined remote use case and compensating controls.

### 13.5 Rotate exposed credentials

OAuth endpoints should be treated as secrets. Rotate a token if it has appeared in logs, screenshots, transcripts or shared configuration.

### 13.6 Name servers by role

Use clear MCP client labels:

```text
hubitat-control
hubitat-engineering
automation-map-context
```

### 13.7 Preserve best-practice acknowledgement

The current write-tool acknowledgement gate is useful as one layer. It should remain, but it is not a substitute for allowlists and server-side policy.

## 14. Evolution path

### Stage 0: contain and document the present architecture

Goals:

- inventory every MCP server and credential;
- identify local versus cloud endpoints;
- disable unnecessary broad write access;
- restore device allowlists;
- rotate exposed tokens;
- record versions and source hashes;
- define server names and ownership.

Exit criteria:

- no routine client holds engineering-wide access;
- every server has a documented purpose;
- every write path is auditable.

### Stage 1: reliable fast household control

Deliver:

- local alias and room registry;
- cached stable device IDs;
- ordinary-action allowlist;
- parallel commands;
- fire-and-forget response mode;
- asynchronous event verification;
- duplicate suppression and rate limits.

Exit criteria:

- common commands usually reach Hubitat in under one second after local intent recognition;
- cloud AI is not required for familiar commands;
- room changes invalidate cached membership.

### Stage 2: read-only context plane

Deliver:

- Automation Map MCP resources;
- export schema validation;
- device/app lookup by stable ID;
- rule-flow explanation;
- dependency-aware warnings;
- scan freshness and uncertainty reporting.

Exit criteria:

- the AI can explain what a command may affect before execution;
- Automation Map remains incapable of writes.

### Stage 3: driver and runtime evidence

Deliver:

- device preference reads;
- driver definitions and user-source reads;
- scheduled jobs;
- logs and events correlated by time;
- strict secret redaction;
- short-lived configuration caches.

Exit criteria:

- questions such as "is auto-off configured?" can be answered through MCP without browser inspection;
- built-in source limitations are reported honestly.

### Stage 4: deterministic policy gateway

Deliver:

- structured command schema;
- device/command/risk classification;
- Observe, Recommend, Control and Engineer profiles;
- confirmations and verification policy;
- kill switch;
- append-only off-hub audit log;
- credential broker and secret store.

Exit criteria:

- no model can directly call a raw token-bearing endpoint;
- sensitive operations are impossible without explicit policy satisfaction.

### Stage 5: hybrid local and advanced AI

Deliver:

- small local intent model for common commands;
- personal memory and aliases;
- cloud Claude integration for planning and recommendations;
- local fallback during WAN/model outage;
- model-independent structured plans;
- recommendation review workflow.

Exit criteria:

- routine control remains local and fast;
- advanced reasoning can be replaced without changing the Hubitat interface;
- cloud unavailability does not break standard household operation.

### Stage 6: modelled recommendations

Deliver:

- automation assessment candidates;
- runtime evidence correlation;
- simulation/dry-run where feasible;
- proposed rule diffs;
- dependency impact reports;
- user-approved deployment through engineering controls.

Exit criteria:

- recommendations cite exact evidence and uncertainty;
- no change is applied merely because a model suggested it.

### Stage 7: protocol evolution

Track MCP developments including:

- incremental/step-up authorisation scopes;
- task-based long-running operations;
- stronger server identity and metadata;
- improved transport support;
- client elicitation and confirmation mechanisms;
- standard audit and policy integration.

Adopt new protocol capabilities only when they simplify the local policy model and remain supported by the chosen clients.

## 15. Likely future end state

```text
                             Cloud Claude
                       advanced reasoning only
                                |
                         redacted context
                                |
                                v
User voice/chat ---> Local personal assistant
                           |
                  intent + memory + aliases
                           |
                           v
                 Deterministic policy gateway
                    /         |          \
                   /          |           \
                  v           v            v
        Automation Map   Hubitat Control   Engineering MCP
          read-only       restricted        session-bound
             |                |                  |
             +----------------+------------------+
                              |
                           Hubitat
             rules, integrations and device mesh
```

In this end state:

- Hubitat still reacts to motion, contact, time and safety events without AI;
- routine conversation is handled locally;
- advanced AI models household behaviour and proposes improvements;
- MCP standardises how both local and cloud clients obtain context and request actions;
- the gateway decides what is allowed;
- Automation Map explains dependencies but cannot change them;
- Codex and Claude Code maintain the software, not the second-by-second home.

## 16. Decision summary

| Question | Position |
| --- | --- |
| Should MCP replace Rule Machine? | No |
| Should every device command require an advanced LLM? | No |
| Should MCP be used for ordinary structured control? | Yes, behind a fast local policy path |
| Should Automation Map gain write tools? | No |
| Should the broad community MCP server remain always write-enabled? | No, not for routine use |
| Should built-in and community MCP servers coexist? | Yes, if their roles and credentials are separated |
| Should driver preferences be exposed through MCP? | Yes, read-only, redacted and engineering-scoped |
| Should cloud AI receive full household context by default? | No |
| Should sensitive actions be available by default? | No |
| Is MCP strategically useful for this home? | Yes, as the governed integration plane |

## Conclusion

The installed applications demonstrate both the promise and the danger of MCP on Hubitat.

The promise is substantial: standardised AI access, local execution, deep diagnostics, rule engineering and a clean path from personalised language to structured home operations.

The danger comes from treating one broad MCP endpoint as a universal trusted remote control. The current community server can reach far beyond ordinary devices, and the presence of two overlapping servers increases ambiguity unless their roles are explicit.

The correct position is not to remove MCP. It is to specialise it:

```text
MCP for context
MCP for constrained control
MCP for time-bounded engineering
different permissions for each
```

That approach preserves Hubitat's local speed and resilience while adding personalisation, advanced reasoning and maintainable AI integration without turning the language model into the home's ungoverned control system.

## References

- [Model Context Protocol architecture](https://modelcontextprotocol.io/specification/2025-11-25/architecture)
- [Model Context Protocol tools specification](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [Model Context Protocol authorisation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [MCP 2026 release-candidate direction](https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/)
- [Hubitat MCP Rule Server](https://github.com/kingpanther13/Hubitat-local-MCP-server)
- [Hubitat Device Detail documentation](https://docs2.hubitat.com/en/user-interface/devices/device-detail)
- [Hubitat App OAuth documentation](https://docs2.hubitat.com/developer/app/oauth)
- [Hubitat Automation Map](https://github.com/GordonThelander/hubitat-automation-map)
- [Hubitat Driver Programmatic Access](../Hubitat%20Driver%20Programmatic%20Access/README.md)
- [Hubitat Read-Only Internal API Harness](../Hubitat%20Read-Only%20Internal%20API%20Harness/README.md)
