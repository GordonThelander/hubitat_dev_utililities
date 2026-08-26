# Observed Visual Rule Builder 2.0 graph format

## Status

**Experimental.** Observed on one Hubitat C-8 running platform 2.5.1.152, using two paused
Visual Rule Builder rules. The more complex fixture was deliberately constructed to cover
two triggers, a condition, true and false branches, reconvergence, device actions, a wait,
a notification, and cross-rule actions.

Evidence markers used here:

| Marker | Meaning |
| --- | --- |
| **[observed-2]** | Present in both live fixtures. |
| **[observed-1]** | Present in the complex fixture only. |
| **[implementation]** | Behaviour of the supplied reference decoder, not a Hubitat guarantee. |
| **[unknown]** | Not established by the available fixtures. |

## Envelope

The MCP read returned a rule with `format: "graph"` and a definition shaped as follows:

```json
{
  "version": 1,
  "nodes": [],
  "edges": []
}
```

- `version` was `1`. **[observed-2]**
- `nodes` and `edges` were arrays. **[observed-2]**
- Forward compatibility rules are unknown. Preserve unknown top-level fields.

## Nodes

Every observed top-level node had:

```json
{
  "id": "unique-node-id",
  "kind": "trigger | decision | action | merge",
  "type": "type-specific-name",
  "config": {}
}
```

`id` is the graph identity used by edges. Do not derive semantics from its human-readable
text. IDs may be generated differently in other rules.

### Trigger nodes

Observed trigger types:

| Type | Configuration |
| --- | --- |
| `contact` | `contactSensors: [deviceId...]`, `contactSensorEvent: String` |
| `motion` | `motionSensors: [deviceId...]`, `motionSensorEvent: String` |

The event strings were already suitable for display. The reference decoder retains them
instead of reconstructing wording from raw fields. **[observed-2]**

### Merge nodes

Observed merge types:

| Type | Role |
| --- | --- |
| `triggerMerge` | Converges multiple triggers before the rule body. |
| `branchMerge` | Reconverges true and false decision branches. |

Merge nodes had empty configuration objects. **[observed-2 for triggerMerge; observed-1
for branchMerge]**

### Decision nodes

The observed decision had:

```json
{
  "kind": "decision",
  "type": "all",
  "config": {
    "conditions": [
      {
        "id": "condition-id",
        "type": "illuminanceCondition",
        "config": {
          "illuminanceSensorState": "Illuminance is below...",
          "illuminance": 50,
          "illuminanceSensors": [102]
        }
      }
    ]
  }
}
```

The decision's `type` described how nested conditions combine. `all` is treated as AND and
`any` as OR by the reference decoder. `any` was not present in the two live fixtures, so
that mapping is an implementation interpretation that requires another fixture.

The actual conditions are nested objects under `config.conditions`; the decision node
itself is not the displayable condition. **[observed-2]**

### Action nodes

Observed actions:

| Type | Configuration | Interpretation |
| --- | --- | --- |
| `turnOn` | `switches: [deviceId...]` | Turn on selected switches. |
| `turnOff` | `switches: [deviceId...]` | Turn off selected switches. |
| `wait` | `minutes`, `seconds` | Delay subsequent actions. |
| `sendNotification` | `notificationDevices`, `notificationMessage` | Send message to selected notification devices. |
| `runRule` | `appId` | Run actions of another installed rule. |

Only `turnOn` occurred in both fixtures. The remaining types occurred once.

## Edges

Every observed edge had:

```json
{
  "from": "source-node-id",
  "to": "destination-node-id",
  "port": "next | true | false"
}
```

- `next` represented ordinary sequential flow. **[observed-2]**
- `true` and `false` represented decision branches. **[observed-1 for false]**
- The simple fixture had a true branch with no false edge and no branch merge. A reader must
  therefore allow an absent false branch. **[observed-1]**

## Device references

In the observed nodes, device IDs were stored in arrays whose keys were `switches` or ended
in `Sensors` or `Devices`. The reference decoder uses this as a heuristic and emits a
warning for an unknown node type. This pattern must not be promoted to a complete VRB
schema without a broader fixture corpus.

## Conservative traversal

The supplied decoder:

- emits all trigger nodes first;
- requires observed triggers to converge on exactly one next node before walking onward;
- skips merge nodes;
- expands a decision into `if`, optional `else`, and `endif` steps;
- follows each branch until a merge or termination;
- resumes after a shared merge;
- bounds every walk to prevent malformed graphs or cycles from hanging;
- reports missing nodes, cycles, ambiguous trigger convergence, and unknown types;
- never guesses a device label when no label mapping exists.

This is an inspection strategy, not a claim about VRB's runtime implementation.

## Access and validation

Two independent MCP surfaces are useful:

- Hubitat's first-party MCP connector can list, read, validate, create, and update VRB rules.
- The community Hubitat Local MCP Server can list/read a visual rule and reports whether it
  is `classic` or `graph` format.

Prefer read and validate operations during research. If a test rule must be created, use a
dedicated paused fixture, obtain user approval, validate first, read it back afterward, and
remove it only with separate approval.

## Privacy

Raw rule definitions can reveal device names, device IDs, notification recipients and
messages, rule IDs, household routines, and security-related behaviour. Sanitise fixtures
before publishing them.

