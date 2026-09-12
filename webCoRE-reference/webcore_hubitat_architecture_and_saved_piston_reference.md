# webCoRE on Hubitat: architecture, saved piston format and static analysis

## 1. Scope

How the Hubitat port of webCoRE stores pistons, and what a read-only tool can extract from them:

- identify webCoRE installations and piston child apps;
- recover and parse the compiled piston document;
- reconstruct statement and expression structure;
- identify direct device reads and actions;
- classify local, webCoRE global and Hub Variable references;
- report unrecognised structure without guessing.

The compiled format is internal to webCoRE, not a published specification. Every claim here is
bound to the pinned source revision and an evidence level.

## 2. Baseline

Source: [`imnotbob/webCoRE@0a37eee2537accd706aaaeeed5a7b4bb0c82646e`](https://github.com/imnotbob/webCoRE/commit/0a37eee2537accd706aaaeeed5a7b4bb0c82646e),
branch `hubitat-patches`, committed 8 August 2026.

| File | Role |
| --- | --- |
| [`webcore.groovy`](https://github.com/imnotbob/webCoRE/blob/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/smartapps/ady624/webcore.src/webcore.groovy) | Parent app: shared settings, permitted devices, function, command and comparison catalogues |
| [`webcore-piston.groovy`](https://github.com/imnotbob/webCoRE/blob/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/smartapps/ady624/webcore-piston.src/webcore-piston.groovy) | Piston child app and runtime executor |
| [`dashboard/`](https://github.com/imnotbob/webCoRE/tree/0a37eee2537accd706aaaeeed5a7b4bb0c82646e/dashboard) | Browser editor that compiles and serialises pistons |

Fixtures were created with IDE `v0.3.114.20220203`. Live checks used webCoRE v0.3.114 on a Hubitat
C-8, platform 2.5.1.181 and later 2.5.1 builds.

At this revision: 109 function handlers, 69 virtual-command handlers, 12 statement types, three
task-policy fields, 48 trigger comparisons and 35 condition comparisons.

Resolve the `hubitat-patches` branch explicitly. The default upstream history leads to
SmartThings-era code.

## 3. Runtime architecture

webCoRE keeps the SmartThings design: a parent app, one child app per piston, a browser editor that
compiles the visual program to compact JSON, and that JSON stored Base64-encoded across numbered
settings.

| Layer | Hubitat representation | Establishes |
| --- | --- | --- |
| webCoRE parent | Installed app | Installation identity, shared configuration, permitted devices |
| Piston | Child installed app | Piston identity, state, saved settings |
| Compiled piston | Base64 JSON in `chunk:N` child settings | Declarations, statements, conditions, operands, tasks |
| Dashboard | External browser editor | Authoring and serialisation |
| Executor | Piston child runtime | Subscriptions, scheduling, evaluation, task execution |

A device permitted on the parent is available to every piston. It is not evidence that any piston
uses it. Piston dependencies come only from the piston's own compiled document.

A paused piston keeps its compiled configuration and can be analysed without running.

## 4. Reading the chunks

The compiled configuration is stored in settings named `chunk:0`, `chunk:1` and so on. On the tested
platform builds they are returned by the internal, undocumented endpoint
`/installedapp/statusJson/<appId>` on the hub's loopback. The parent's permitted devices are in the
same endpoint's capability settings (`deviceIdsForDeviceList`).

Decode sequence:

1. select setting names matching `chunk:<non-negative integer>` exactly;
2. require `chunk:0`, a contiguous sequence, no duplicates and no empty chunk;
3. enforce index and total-length limits;
4. concatenate in numeric order and Base64-decode;
5. decode as UTF-8;
6. replace webCoRE emoji escapes (`:%XX%XX%XX%XX:`);
7. parse JSON and require an object root.

No chunks at all means a piston that was never saved (`not-present`), not corruption.

Implementation: [`snippets/01_decode_piston_chunks.groovy`](snippets/01_decode_piston_chunks.groovy).

Base64 is encoding, not encryption. Treat raw chunks and the decoded document as sensitive.

### 4.1 Stored and in-memory forms differ

The executor's `cleanCode(item, inMem)` strips editor fields and defaults only from the in-memory copy.
The stored copy can carry expression display text, comments, empty lists and default policy values the
executor never reads. That is normal.

### 4.2 Opening and saving changes the stored shape

Opening a piston reconstructs it through the child app, which can assign `$` node numbers, recompute
subscription flags, add `ct` to conditions, events and switches, and generate transient warnings. The
Dashboard then drops warnings and false, null or empty values when saving.

A newly added node can therefore differ from the same node after a reopen-and-save. Accept both forms.
`ct` is written by `subscribeAll` during reconstruction, so a first-save node has none, and a saved
`ct` can be stale after an edit.

## 5. The compiled document

| Root key | Content |
| --- | --- |
| `s` | Root statement list |
| `v` | Piston-local variable declarations |
| `o` | Piston options, read by the executor's `gtPOpt` (`mps`, `pep`, `ced`, `cto`, `dco`, `des`, `aps`, `ish`) |

Root `id`, `n`, `r`, `rop` and `l` are also saved; their consumers are not traced (see
[known_gaps.md](known_gaps.md)). Statements own condition, branch, case, event and task lists. Operands appear in conditions, task
parameters, loop controls and variable declarations.

Short keys mean different things in different owners: `s` is a statement list under `if`, a
subscription flag on a condition, and a preset name on a `t: 's'` operand. Decode by structural path
and owner type, never by a global key dictionary.

Grammar: [statement_and_operand_catalogue.md](statement_and_operand_catalogue.md).

### 5.1 Worked example

A reduced round-trip capture: if the switch on device 2 is `on`, set device 3 to level 40.

```json
{
  "s": [
    {
      "$": 1, "t": "if", "a": "0", "o": "and", "tcp": "c",
      "c": [
        {
          "$": 2, "t": "condition", "co": "is", "ct": "c",
          "lo": { "t": "p", "vt": "string", "a": "switch", "f": "l",
                  "d": [":32e164a2e42f34f83c6d5126248c9a8d:"] },
          "ro": { "t": "c", "vt": "enum", "c": "on", "f": "l",
                  "exp": { "t": "expression", "str": "on", "ok": true,
                           "i": [{ "t": "string", "v": "on", "ok": true }] } }
        }
      ],
      "s": [
        {
          "$": 3, "t": "action", "a": "0", "tcp": "c",
          "d": [":80ff8adc87cc6d0d790b1bb31708f282:"],
          "k": [{ "$": 4, "c": "setLevel",
                  "p": [{ "t": "c", "vt": "integer", "c": "40", "f": "l" }] }]
        }
      ],
      "ei": [], "e": [], "r": []
    }
  ],
  "v": []
}
```

`lo.vt` is `string` and `ro.vt` is `enum` exactly as the editor saved them; value types are not
normalised across the two sides of a comparison.

What it yields:

| Position | Result | Snippet |
| --- | --- | --- |
| `$.s[0]` | `if` statement, one condition, then-branch of one action | 05 |
| `$.s[0].c[0].lo.d[0]` | Token for device ID `2`, resolved against the parent's permitted devices | 02 |
| `$.s[0].c[0]` | `co: is` is a condition comparison and `ct: c` agrees: device 2 `switch` is a constraint read | 04 |
| `$.s[0].c[0]` | Label `<device 2 name>'s switch is on` | 06 |
| `$.s[0].s[0].d[0]` | Action on device ID `3` | 02, 04 |
| `$.s[0].s[0].k[0]` | Label `setLevel(40)` | 06 |

## 6. Accounting, recognition, structure, semantics

Keep four measures separate:

| Measure | Question |
| --- | --- |
| Accounting | Was every object, list, field and scalar visited within bounds? |
| Recognition | Did each position match a known construct? |
| Structure | Is the saved form proven by source and round trip (L3)? |
| Semantics | Is the runtime meaning proven (L4)? |

Levels: [evidence_model_and_fixture_method.md](evidence_model_and_fixture_method.md). Walker:
[`snippets/05_bounded_structure_walker.groovy`](snippets/05_bounded_structure_walker.groovy).

## 7. Interpretation boundary

This is the single statement of what static analysis cannot claim. Other files link here.

- Saved structure is not an execution trace. It does not show which branch ran, what a dynamic operand
  returned, whether a restriction allowed execution, or whether a task succeeded.
- A readable label transcribes stored spelling. `Patio Door's contact is closed` does not mean the
  condition was ever true.
- Unknown is not absent. Missing decoder output is not proof of non-use.
- Parent permission is not piston use.
- A shared device does not prove two pistons fire from the same event.
- One fixture proves its form, not every form from every editor version.
- Structural recognition does not justify writing chunks, claiming behavioural equivalence with
  another engine, or judging an automation safe.

## 8. Analyser requirements

1. Pin and fingerprint the webCoRE source revision.
2. Identify each piston and its owning parent app.
3. Decode chunks within fixed limits.
4. Visit every map, list, field and scalar once, within depth and count budgets.
5. Classify constructs by structural position against a source-pinned registry.
6. Report unrecognised positions as fixed reason codes with structural paths.
7. Resolve device tokens only against the owning parent's permitted devices.
8. Record variable declarations, reads and writes separately.
9. Record device reads and actions separately.
10. Transcribe only allowlisted operands; fall back to a bare name rather than a partial label.
11. Fail a malformed piston closed without stopping discovery of other apps.
12. Discard raw and decoded configuration once the result is built.

## 9. Source drift

The compiled format is not a stable API. Each registry entry carries:

- repository, branch and commit;
- the source assertion that recognises the construct;
- the evidence level reached;
- the fixture that supports the promotion;
- a fixed reason when evidence is withdrawn.

When the pin changes, rerun the assertions. A failed assertion withdraws the claim.

## 10. Privacy contract

| Data | Treatment |
| --- | --- |
| Raw chunks, decoded document | Never logged, cached or exported |
| Unmatched device tokens | Never exposed |
| Variable values (local, `@`, `@@`) | Never read |
| Variable names | Printed where an operand references them |
| Saved constants in conditions and task parameters | Printed in labels, for example `setLevel(40)` |
| Unsupported or device-valued parameters | Omitted; the label falls back to the command name |
| Errors | Fixed reason codes and counts, never raw content |

Saved constants do reach labels and exports. State that in any user-facing privacy text.
