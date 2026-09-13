# webCoRE on Hubitat developer reference

A source-pinned reference for reading and statically analysing webCoRE pistons on Hubitat: how pistons
are stored, the compiled grammar, device and variable resolution, the evidence levels behind each
claim, and tested Groovy implementations of the core contracts.

## Contents

| Path | Purpose |
| --- | --- |
| `webcore_hubitat_architecture_and_saved_piston_reference.md` | Architecture, persistence, compiled document, worked example, interpretation boundary |
| `statement_and_operand_catalogue.md` | Statement types, nested structures, operand kinds, comparisons and `ct` |
| `function_and_virtual_command_catalogue.md` | The 109 functions and 69 virtual commands implemented at the pinned revision |
| `device_and_variable_resolution.md` | Device tokens, reads and actions, variable namespaces and direction |
| `evidence_model_and_fixture_method.md` | Evidence levels L0 to L4, promotion rules, fixture capture |
| `automation_map_application.md` | A production application of the model |
| `known_gaps.md` | Constructs and behaviour not yet proven |
| `snippets/README.md` | Local and Hubitat execution instructions for the reference code |
| `snippets/contracts/` | Reusable, self-checking Groovy logic for the six reference contracts |
| `snippets/hub-ready-apps/` | Complete, installable read-only Hubitat apps |
| `snippets/contracts/01_decode_piston_chunks.groovy` | Decode bounded, contiguous `chunk:N` settings into a JSON object |
| `snippets/contracts/02_device_hash_lookup.groovy` | Build and resolve a parent-scoped webCoRE device-token index |
| `snippets/contracts/03_variable_namespaces_and_roles.groovy` | Classify variable namespaces and derive read/write direction |
| `snippets/contracts/04_device_reads_and_actions.groovy` | Extract direct device reads, roles and action targets |
| `snippets/contracts/05_bounded_structure_walker.groovy` | Account for saved structure within fixed traversal budgets |
| `snippets/contracts/06_flow_labels.groovy` | Render bounded, all-or-nothing plain-text flow labels |
| `snippets/hub-ready-apps/07_webcore_reference_harness.groovy` | Installable read-only Hubitat app that runs all six contracts |
| `snippets/hub-ready-apps/08_wc_local_variable_extract.groovy` | Installable read-only Hubitat app that lists one piston's declared local variables and whether they are referenced |

The standalone local-variable extractor remains at its
[original public location](https://github.com/GordonThelander/hubitat-automation-map/blob/dev/tools/webcore-investigation/wc-local-variable-extract.groovy).
The hub-ready copy is retained here so the reusable app is discoverable with the reference.

## Baseline

- Source: [`imnotbob/webCoRE@0a37eee`](https://github.com/imnotbob/webCoRE/commit/0a37eee2537accd706aaaeeed5a7b4bb0c82646e),
  branch `hubitat-patches`, committed 8 August 2026.
- Editor: IDE `v0.3.114.20220203`.
- Live: webCoRE v0.3.114 on a Hubitat C-8, platform 2.5.1.181 and later 2.5.1 builds.
- Fixtures: a small, deliberately constructed corpus captured through the editor.

## Evidence terms

| Term | Meaning |
| --- | --- |
| Source-proven | The consumer or executor path is traced in the pinned source |
| Fixture-proven | The shape survives an editor save and an unchanged reopen-and-save round trip |
| Live-observed | Reproduced on a Hubitat hub |
| Not proven | Recorded, but not sufficient for an implementation claim |
| Unknown | A valid result, never collapsed into absent, false or unused |

## Scope

Read-only inspection, documentation, dependency mapping and static analysis. The compiled format is
internal to webCoRE: do not write chunks directly or infer runtime behaviour from saved structure
alone (see architecture section 7).

## License

This reference and its snippets are covered by the repository's [Apache License 2.0](../LICENSE).
webCoRE itself is a separate Apache 2.0 project; names and facts cited from its source remain its
authors' work.
