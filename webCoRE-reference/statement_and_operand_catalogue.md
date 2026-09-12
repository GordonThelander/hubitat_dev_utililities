# Statement and operand catalogue

Saved grammar at the pinned revision. Keys are meaningful only inside their owner. This is a reading
map, not a schema for writing pistons.

## Statement envelope

| Key | Meaning |
| --- | --- |
| `t` | Statement type |
| `$` | Node number, assigned on reconstruction and persisted after reopen-and-save |
| `a` | Asynchronous flag, `'1'` when enabled |
| `tep` | Task execution policy |
| `tsp` | Task scheduling policy |
| `tcp` | Task cancellation policy |
| `r` | Restriction list |
| `rop` | Restriction-list operator |
| `rn` | Restriction negation |
| `di` | Disabled marker |
| `z`, `zc` | Description and comment |
| `sm` | Metadata, meaning not proven |

The three policy fields are executor inputs (L4 for existence). Individual non-default policy
combinations are not proven.

## Statement types

| Type | Saved structure | Proven meaning |
| --- | --- | --- |
| `action` | `d` targets, `k` tasks | Apply tasks in order to the target devices |
| `if` | `c`, `o`, `n`, `s`, `ei`, `e` | Test conditions; then, else-if, else in order |
| `while` | `c`, `s` | Pre-condition loop |
| `repeat` | `s`, `c` | Body, then until-condition check |
| `every` | `lo`, `lo2`, `lo3`, `s` | Block owned by its own timer |
| `on` | events `c`, body `s` | Run the body when any event matches |
| `each` | devices `lo`, variable `x`, body `s` | Iterate devices through the variable |
| `for` | `lo`, `lo2`, `lo3`, variable `x`, body `s` | Stepped iteration from start to end |
| `switch` | `lo`, `ctp`, cases `cs`, default `e` | Ordered cases with break or fall-through |
| `do` | body `s` | Run contained statements once, in order |
| `break` | none | Leave the directly containing loop or switch |
| `exit` | `lo` | Set piston state from `lo` and terminate |

### `if`

| Key | Content |
| --- | --- |
| `c` | Conditions |
| `o` | Condition-list operator |
| `n` | Negation |
| `s` | Then-statements |
| `ei` | Ordered else-if records, each with `$`, `o`, `n`, `c`, `s` |
| `e` | Else-statements |

### `switch`

| Key | Content |
| --- | --- |
| `lo` | Value under test |
| `cs` | Ordered cases |
| case `t: 's'` | Single value in `ro` |
| case `t: 'r'` | Range `ro` to `ro2` |
| `ctp: 'i'` (or absent) | Stop after the first matching case |
| `ctp: 'e'` | Fall through to later cases and the default until a `break` |
| `e` | Default, runs when no case matched, or in fall-through when no `break` intervened |

Case order and default behaviour are L4. Case value forms are not all transcribed.

## Nested structures

| Structure | Keys |
| --- | --- |
| Case | `$`, `t`, `ro`, `ro2`, `s`, `z` |
| Event | `$`, `t: 'event'`, `lo`, `ct`, `s`, `sm`, `z` |
| Task | `$`, command `c`, parameters `p`, `m`, `cm`, `a`, `z` |
| Condition | `t: 'condition'`, `lo`, `co`, `ro`, `ro2`, `to`, `to2`, `ct`, `fs`, `ts` |
| Condition group | `t: 'group'`, conditions `c`, operator `o`, negation `n`, `fs`, `ts` |

### Followed-by

A condition list with operator `followed by` is an ordered timed sequence, never a boolean `and`.
Later steps, conditions or groups, carry `wd` (wait delay operand) and `wt` (`l` loose, `s` strict,
`n` negated). Timing semantics are not proven.

## Operand kinds

Twelve registry identities. Eleven are L3.

| Identity | Discriminator | Keys | Meaning | Level |
| --- | --- | --- | --- | --- |
| Constant | `t: 'c'` | `vt`, `c`, `exp` | Saved literal and value type | L3 |
| Virtual | `t: 'v'` | `vt`, `v` | System value such as `time` or `mode` | L3 |
| Variable | `t: 'x'` | `vt`, `x`, `xi` | Local, `@` global or `@@` Hub Variable | L3 |
| Expression | `t: 'e'` | `vt`, `exp` | Pre-parsed expression tree | L3 |
| Physical | `t: 'p'` | `vt`, `a`, `d`, `g` | Attribute `a` read from devices `d` | L3 |
| Preset | `t: 's'` | `vt`, `s` | Time preset such as sunrise | L3 |
| Device list | `t: 'd'` | `d` | Device selection without an attribute | L3 |
| Argument | `t: 'u'` | `vt`, `u` | Named entry of `$args` | L3 |
| Event-match physical | `t: 'p'` in an event | `a`, `d` | Match by attribute and device membership | L3 |
| Event-match virtual | `t: 'v'` in an event | `v` | Match by virtual event name | L3 |
| Event-match variable | `t: 'x'` in an event | `x` | Match by variable name | L3 |
| Empty | `t: ''` | `vt` | Optional, nothing selected | L2 |

**Empty is source-proven but not editor-producible.** The executor dispatches `t: ''`, and the editor
holds it in memory (shown as "(no value set)"), but both first save and round trip store the operand
with no `t` key. That stored form is a separate construct, `wc.task-parameter.unselected`.

`exp` is already parsed: `{"t":"expression","i":[{"t":"integer","v":2,"ok":true}],"str":"2","ok":true}`.
No expression-text parser is needed to read it.

## Comparisons and `ct`

The parent's `getChildComparisons()` defines 48 trigger comparisons and 35 condition comparisons (full
lists in [`snippets/04_device_reads_and_actions.groovy`](snippets/04_device_reads_and_actions.groovy)).
`is_true`, `is_false`, `was_true` and `was_false` are commented out in source and not in the live set.

`ct` records which block applied: `t` trigger, `c` condition. `subscribeAll` can downgrade a trigger
comparison to `ct: 'c'`, and `ct` can be stale after an edit. Classify a condition as:

| `co` membership | `ct` | Role |
| --- | --- | --- |
| trigger or condition | absent | From membership |
| trigger or condition | agrees | From membership |
| trigger or condition | conflicts | Unknown |
| neither | any | Unknown |
| any | unrecognised value | Unknown |

An event under `on` is a trigger by position; no comparison test applies.

## Functions and virtual commands

Expression function nodes (`t: 'function'`, name `n`, arguments `i`) dispatch to `func_<name>`.
Tasks store the command in `c` and parameter operands in `p`, dispatched to `vcmd_<command>` or a
device command. Catalogue: [function_and_virtual_command_catalogue.md](function_and_virtual_command_catalogue.md).

The editor does not keep an unknown function as a callable node. A fixture for that path must be saved
and reopened through the editor, not hand-written.

## Proven semantics (L4)

- `if`: then, else-if, else in order; condition-list negation and `or`.
- Followed-by kept as an opaque ordered timed group.
- `do`: sequential body.
- `switch`: ordered cases, default.
- `break`: scope of the direct containing loop or switch.
- `exit`: terminates the piston.
- `while` pre-condition, `repeat` post-condition.
- `for` stepped iteration, `each` device iteration.
- `on`: any-event match.
- `every`: own timer.
- Execution, scheduling and cancellation policies exist.
- `action`: target selection and task order.

Not proven: restrictions, asynchronous execution, resumed and fast-forward execution, dynamic device
selection, individual comparison meaning.
