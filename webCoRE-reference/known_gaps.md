# Known gaps

What is not proven at the pinned revision. For what static analysis can never show, see
[architecture section 7](webcore_hubitat_architecture_and_saved_piston_reference.md#7-interpretation-boundary).

## Identity

| Gap | State |
| --- | --- |
| webCoRE globals (`@name`) | Stored in the parent under hashed names; not reconciled to stable identities |
| Root keys `id`, `n`, `r`, `rop`, `l` | Saved; consumers not traced |
| Root option keys in `o` (`mps`, `pep`, `dco`, `des`, `aps`, `ish`, `ced`, `cto`) | All eight are read through `gtPOpt`; individual meanings not traced |

## Structure

| Gap | State |
| --- | --- |
| `sm` | Saved on conditions, groups and events; meaning not proven |
| Absent `tcp` | Not captured from an editor path |
| Task `cm` and optional `a` | Not captured |
| `for` without `x` | Not captured |
| Followed-by `wd` retained where unconsumed | Not proven |
| Nodes present only before the first reopen | Not captured |
| Switch case values | Order and default proven; case value forms not transcribed |
| Empty operand `t: ''` | L2: source-proven, not editor-producible |

## Semantics

| Gap | State |
| --- | --- |
| Restrictions (`r`, `rop`, `rn`) | Not proven |
| Asynchronous flag `a` | Not proven |
| Individual policy combinations (`tep`, `tsp`, `tcp`) | Existence proven; combinations not proven |
| Followed-by timing | Not proven |
| Resumed and fast-forward execution | Not proven |
| Individual comparison meaning | Not proven |
| Dynamic device selection | Not resolvable statically |

## Closing a gap

A gap closes with a narrow claim backed by the relevant combination of: a traced source path, a
first-save fixture, an unchanged round trip, a targeted edit round trip, a discriminating live
observation, and a verifier that fails on source or shape drift. Community examples locate candidate
shapes; they do not replace fixtures.
