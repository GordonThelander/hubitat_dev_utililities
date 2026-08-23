# Rule Machine Storage

Reusable research notes for developers building read-only tools that inspect Hubitat Rule
Machine 5.1 rules.

The material was produced while developing Automation Map. It is published separately so
other Hubitat developers can reuse the findings without depending on Automation Map or
copying its implementation.

## Contents

| File | Status | Purpose |
| --- | --- | --- |
| `rule_machine_5_1_storage_format.md` | Completed reference | Empirically documents the private Rule Machine 5.1 storage representation exposed by the hub. |
| `rule_machine_execution_and_cross_rule_causality.md` | Research reference | Separates documented execution behaviour, author explanations, local observations, implemented extraction, and unvalidated proposals. |

## Start here

Read `rule_machine_5_1_storage_format.md` first. It is the reusable implementation reference.
The execution and causality document is broader source material and contains explicitly
unfinished validation work. Do not treat every proposal in it as implemented or proven.

The storage reference explains how to reconstruct:

- triggers and trigger devices;
- conditions and condition operands;
- ordered actions and their separately stored parameters;
- Rule Machine rule-to-rule actions;
- Hub Variable reads and writes;
- Required Expression data;
- scheduled jobs and event subscriptions;
- confidence levels and known traps in the private format.

## Safety boundary

This is documentation for **read-only inspection**. It is not a supported Rule Machine API
and it is not a specification for writing Rule Machine state.

- The described fields and internal endpoints are undocumented and may change in any
  Hubitat platform release.
- Never write reconstructed `appState` or `appSettings` back to a rule.
- Treat the rule's own Hubitat UI page as the ground truth.
- Preserve unknown fields and refuse to infer semantics that have not been demonstrated.
- Do not use the stored representation as an independent Rule Machine execution engine.
- Redact secrets before sharing endpoint output. The same generic installed-app endpoint
  can expose tokens, passwords, account identifiers, and private URLs for non-Rule-Machine
  applications.

## Evidence base

The completed reference was derived on a Hubitat C-8 running platform 2.5.1.142 and checked
against 38 Rule-5.1 rules. Additional deliberately constructed fixtures covered features
that the production corpus did not exercise. Every technical claim is marked as invariant,
strong, limited, single, heuristic, or unknown.

The main discovery method was differential, read-only comparison:

1. Read the stored representation of a known rule.
2. Compare it with the rule rendered by Hubitat.
3. Change one feature in a controlled fixture.
4. Identify the corresponding storage change.
5. Check the proposed interpretation across the wider corpus.
6. Build a decoder and compare its output with the Hubitat rule page.

## Access routes

The original research used Hubitat's internal read-only installed-app status endpoint:

```text
GET /installedapp/statusJson/<installedAppId>
```

Modern tooling may offer higher-level read paths. Hubitat's first-party MCP connector can
list apps and Visual Rule Builder rules. The community Hubitat Local MCP Server can inspect
Rule Machine inventory, health, app configuration, local variables, dependencies, logs,
and scheduled jobs. Those MCP surfaces are useful for discovery and validation, but they do
not turn the private Rule Machine storage format into a supported public contract.

## Reuse guidance

When implementing a reader:

- pin observations to the hub platform version and Rule Machine version;
- retain raw unrecognised values alongside decoded output;
- keep storage reconstruction separate from runtime prediction;
- test against deliberately constructed fixtures as well as real rules;
- verify every decoded rule against its Hubitat UI representation;
- fail visibly when a field is unknown instead of manufacturing a plausible meaning;
- treat live event subscriptions as a snapshot because Rule Machine can remove trigger
  subscriptions while a Required Expression is false.

## Provenance

Source project: [GordonThelander/hubitat-automation-map](https://github.com/GordonThelander/hubitat-automation-map)

The two research documents are copied from that project's `Supporting Docs` directory so
their original evidence markers, warnings, and historical context remain intact.

