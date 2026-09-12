# Application: Automation Map

Automation Map is a read-only Hubitat app that maps devices, apps, rules and variables. It decodes
webCoRE pistons with the model in this reference, and v2.3.0 adds piston flowcharts. Automated
migration of pistons to Rule Machine or Visual Rule Builder was considered and put aside because of
its complexity and expected low user adoption.

## Relationships

| Compiled evidence | Relationship | Direction |
| --- | --- | --- |
| Hub Variable or local read | Read | Variable to piston |
| Hub Variable or local write | Write | Piston to variable |
| Physical operand of an `on` event | Trigger | Device to piston |
| Physical operand in a condition, role agreed | Trigger or constraint | Device to piston |
| Any other physical operand | Device read | Device to piston |
| Resolved action target | Action | Piston to device |

The webCoRE parent's permitted devices are never drawn as edges. Both directions are kept when a
piston reads and acts on one device, or reads and writes one variable.

## Flowchart

A piston renders as:

| Construct | Rendering |
| --- | --- |
| `on` event | Trigger node, `When <attribute> changes` |
| `if` trigger conditions | Trigger nodes |
| `if` remaining conditions | Decision with transcribed condition text |
| Else-if, else | Branches in saved order |
| Condition groups | Bracketed text, nested to depth 6 |
| `action` | One node per task, `command(params)` when every parameter renders |
| `switch` | Ordered `case not decoded` placeholders; default not drawn |
| `while`, `repeat`, `for`, `each` | Enter and exit blocks |
| `every` | Timer trigger node |
| `do`, `break`, `exit` | Body inline, `break`, `exit piston` |

Any condition part that cannot be named in full collapses the label to `condition not decoded`.

## Decode Coverage

An on-demand, per-piston walk of the whole saved tree. It reports traversal status, recognised
positions with evidence levels, unidentified positions as fixed reasons and paths, proven and unproven
statement meanings, and withdrawn claims after source drift. It will not run during a scan.

## Failure handling

| Case | Result |
| --- | --- |
| Malformed chunks or JSON | Fixed decoder error; scan completes with gaps |
| Unresolved token | Reconciliation gap counted; no edge |
| Runtime-selected or variable-backed device | Partial coverage; not a gap in scan status |
| Any piston failure | Other apps still discovered |
| Inert piston | Only when decoding is complete and no relationship exists |

## Export

The graph, focus views, search, Insights and the AI export share one derived model, so a device has
the same role everywhere. The export carries relationships and rendered labels, never the decoded
document, raw chunks, unmatched tokens or variable values.

## Coverage at v2.3.0

- 12 of 12 statement types have L3 structure and at least one L4 claim.
- 11 of 12 operand identities are L3; the empty operand is L2.
- 280 registered constructs: 23 at L3, 257 at L2.
