# webCoRE reference snippets

Small, self-checking Groovy implementations of the contracts in this reference. Each file runs
unchanged under local Groovy and pastes into a Hubitat app as ordinary methods.

| File | Contract | Reference section |
| --- | --- | --- |
| `01_decode_piston_chunks.groovy` | `chunk:N` selection, bounds, Base64, UTF-8, emoji, JSON root | Architecture 5 |
| `02_device_hash_lookup.groovy` | Device token formula, parent permitted devices, unique resolution | Device and variable resolution 2 |
| `03_variable_namespaces_and_roles.groovy` | Local, `@`, `@@`, `$` identity; read/write by position | Device and variable resolution 6 to 10 |
| `04_device_reads_and_actions.groovy` | Device reads with trigger/constraint role, actions, fixed issue codes | Device and variable resolution 3 to 5 |
| `05_bounded_structure_walker.groovy` | Depth, visit and scalar budgets; path-only findings | Evidence model 3 |
| `06_flow_labels.groovy` | Plain-text task, condition and event labels, all-or-nothing | Statement and operand catalogue |
| `07_webcore_reference_harness.groovy` | Installable read-only Hubitat app containing all six contracts | This directory |

## Run locally

```bash
groovy 01_decode_piston_chunks.groovy
```

Files 01 to 06 end with assertions and print one `PASS` line. Local execution verifies the logic,
not Hubitat runtime compatibility.

## Run the complete harness on Hubitat

1. Open **Apps Code** in the Hubitat administrative interface.
2. Select **New App**, paste `07_webcore_reference_harness.groovy`, then save it.
3. Open **Apps**, select **Add User App**, then install **webCoRE Reference Harness**.
4. Select **Run contract self-checks**. The six contracts must all report `PASS`.
5. To inspect a live piston, open that piston from Hubitat's Apps page and copy the numeric installed-
   app ID from `/installedapp/configure/<id>/main` in the browser address. Enter that ID in the
   harness. The harness obtains and verifies the owning parent from the piston's status data.

The harness reads the selected installed apps through Hubitat's loopback `statusJson` endpoint. It
does not write to webCoRE or retain decoded content. Its saved report contains only fixed result
codes and aggregate counts. The endpoint is internal and undocumented, so rerun the contract checks
after a Hubitat platform update.

## Reuse individual contracts on Hubitat

Copy the methods above the `---- self-check ----` line into the app. The snippets use no top-level
static fields or methods, no `System.currentTimeMillis()`, and only Groovy and Java classes that
Hubitat apps can import (`JsonSlurper`, `MessageDigest`, `URLDecoder`).

Platform access is injected rather than called inside the logic:

- **Status JSON.** `decodePistonChunks` and `permittedDeviceIds` take the map returned by
  `/installedapp/statusJson/<appId>`. On the hub, fetch it with `httpGet` against
  `http://127.0.0.1:8080` (example in `01`). The endpoint is internal and undocumented.
- **Token resolution.** `collectDeviceRelationships` takes a `resolveToken` closure. Build it from
  `02`: `{ String t -> resolveDeviceToken(t, indexForThisPistonsParent) }`.
- **Device names.** `conditionText` takes a token-to-name map built after resolution.

The walker in `05` is bounded by counts and depth, so it needs no clock. If a time budget is added,
pass a closure (`{ now() }` on the hub, a fixed counter in the self-check).

## Output rules

Results carry names, IDs, fixed codes, counts and structural paths. They never carry raw chunks, the
decoded document, unmatched hashes or variable values. Labels from `06` are plain text; escape them
for the output medium.
