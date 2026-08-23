# Visual Rule Builder Storage

Experimental, fixture-backed notes and reference code for reading Hubitat Visual Rule
Builder 2.0 graph rules.

This package was extracted from Automation Map's working VRB decoder and checked against
two live, paused VRB rules on a Hubitat C-8 running platform 2.5.1.152. It is deliberately
more cautious than the Rule Machine storage reference because the observed corpus is still
small.

## Contents

| Path | Purpose |
| --- | --- |
| `observed_graph_format.md` | Documents the graph representation, evidence, limitations, and safe interpretation rules. |
| `vrb_graph_decoder_reference.groovy` | Standalone conservative decoder for the observed graph shape. It does not write to the hub. |
| `verify_fixtures.groovy` | Executable check that decodes both fixtures and compares them with the expected steps. |
| `visual_rule_builder_graph_observed.schema.json` | Permissive JSON Schema for the observed envelope, nodes, and edges. Unknown node types remain allowed. |
| `fixtures/simple_graph.json` | Sanitised trigger, merge, decision, and action fixture. |
| `fixtures/complex_graph.json` | Sanitised two-branch fixture with wait, notification, cross-rule actions, and reconvergence. |
| `fixtures/device_labels.json` | Synthetic device and app labels used by the expected-output fixtures. |
| `fixtures/simple_expected_steps.json` | Expected conservative decoding for the simple fixture. |
| `fixtures/complex_expected_steps.json` | Expected conservative decoding for the complex fixture. |

## What is established

- Graph rules use a `version`, `nodes`, and `edges` envelope.
- Nodes carry `id`, `kind`, `type`, and `config`.
- Edges carry `from`, `to`, and `port`.
- Multiple triggers converge through a `triggerMerge` node in both observed rules.
- A decision stores nested condition objects under `config.conditions`.
- Decision edges use `true` and `false`; ordinary flow uses `next`.
- Branches can reconverge through a `branchMerge` node.
- Device references are numeric IDs in type-specific configuration arrays.
- A `runRule` action stores the target installed-app ID in `config.appId`.

These are observations, not a public Hubitat contract.

## What is not established

- A complete catalogue of VRB trigger, condition, and action types.
- Whether every builder path always emits the same merge-node conventions.
- Compatibility across older or future Hubitat platform builds.
- Whether graph `version` will remain `1`.
- Safe direct mutation of stored application state.
- General execution semantics beyond what Hubitat documents.

## Safe use

Use this package to inspect, visualise, export, compare, or conservatively decode rules.
Do not write `graphDocument` directly into application state.

For authoring, prefer Hubitat's supported Visual Rule Builder UI or the first-party MCP
connector's VRB validation and create/update tools. Always validate a proposed rule before
creating or updating it, then read it back and compare the stored definition with the
intended rule.

Unknown node types must remain visible. The reference decoder emits a generic label and a
warning instead of assigning guessed semantics.

## Verify the fixtures

With Groovy installed, run this from the directory containing this README:

```text
groovy verify_fixtures.groovy
```

The verifier parses both sanitised graphs with the standalone decoder and requires an exact
match with the checked-in expected steps.

## Fixture provenance and privacy

The fixtures preserve the shapes of two deliberately constructed test rules, but all hub
device IDs, installed-app IDs, labels, and notification text are synthetic. They contain no
tokens, addresses, account data, or production identifiers.

## Recommended validation expansion

Contributions should add one minimal fixture per newly observed node type and record:

1. Hub model and platform build.
2. VRB format and graph version.
3. The rule as displayed by Hubitat.
4. The sanitised stored graph.
5. Expected decoded steps.
6. Whether the result was checked through the UI, first-party MCP, community MCP, or more
   than one route.

Do not broaden the decoder from a single plausible example without marking the evidence as
limited.

## Provenance

Derived from the VRB decoder in
[GordonThelander/hubitat-automation-map](https://github.com/GordonThelander/hubitat-automation-map).
