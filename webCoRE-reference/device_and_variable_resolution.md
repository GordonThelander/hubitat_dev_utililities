# Device and variable resolution

## 1. Parent permissions

The webCoRE parent holds the devices its pistons may use. That set is a permission, not a use. Build
piston-to-device relationships from the piston's compiled document; use the parent only to resolve
tokens.

## 2. Device tokens

A direct device reference in a piston is a token, not a device ID:

```text
":" + lowercaseHex(MD5("core." + deviceId)) + ":"
```

Device ID `2` gives `:32e164a2e42f34f83c6d5126248c9a8d:`. The source is `hashId()` in `webcore.groovy`.

The token is not salted per parent: the same device ID gives the same token under every webCoRE
parent. Resolve each piston's tokens only against its own parent's permitted devices, so one
installation's permissions never resolve another installation's piston.

| Condition | Result |
| --- | --- |
| Not `:` + 32 lowercase hex + `:` | `not-a-device-token` |
| No permitted device produces it | `no-match` |
| More than one permitted device produces it | `ambiguous` |
| Parent missing or unreadable | `missing-parent-index` |
| Exactly one permitted device | Resolved device ID |

Never resolve by label and never create a device from an unmatched token.

Implementation: [`snippets/contracts/02_device_hash_lookup.groovy`](snippets/contracts/02_device_hash_lookup.groovy).

## 3. Device reads

A physical operand (`t: 'p'`) reads attribute `a` from the devices in `d`.

| Position | Role |
| --- | --- |
| Operand of an `on` event | Trigger |
| Condition in `c` of `if`, else-if, `while` or `repeat`, or inside a group | From `co` membership and `ct` (see [catalogue](statement_and_operand_catalogue.md#comparisons-and-ct)) |
| Expression, calculation, task parameter, restriction | Device read, no role |
| Conflicting or unrecognised `co`/`ct` | Device read, no role |

## 4. Device actions

An `action` statement lists targets in `d` and tasks in `k`; each task has command `c` and parameter
operands `p`. A resolved target is an action relationship; the command names travel with it.

When a piston reads and acts on the same device, keep both relationships.

Implementation: [`snippets/contracts/04_device_reads_and_actions.groovy`](snippets/contracts/04_device_reads_and_actions.groovy).

## 5. Device references that cannot be resolved statically

| `d` entry | Issue code |
| --- | --- |
| `$currentEventDevice` | `runtime-selected-device` |
| `@name` or `@@name` (variable-backed list) | `variable-backed-device-list` |
| Any other string (local variable name, location or virtual device form) | `non-physical-device` |
| Non-string entry | `malformed-device-node` |

These make device coverage partial. They never mean the piston uses no device.

## 6. Variable namespaces

| Saved name | Namespace | Identity |
| --- | --- | --- |
| `$name` | System variable | Not a dependency |
| `@@name` | Hubitat Hub Variable | Hub-wide, `name` without prefix |
| `@name` | webCoRE global | Per webCoRE installation, not resolved (see [known gaps](known_gaps.md)) |
| `name` declared in root `v` | Piston local | Scoped to the piston |
| `name` not declared | Undeclared | Report, do not merge |

Classify before stripping prefixes. A trailing single index (`list[2]`) is removed first, and local
names use webCoRE's space-to-underscore form (`room list` becomes `room_list`).

## 7. Local declarations

Each entry in root `v` has name `n`, type `t` and optionally an initial value operand in `v`. A device
variable's initial value is a device-list operand whose `d` holds tokens. Report each local as declared
only, read, written, or both.

The installable [`WC Local Variable Extract`](snippets/hub-ready-apps/08_wc_local_variable_extract.groovy) provides
a focused, read-only inspection of these declarations on a Hubitat hub. Its maintained source remains
at the [original public location](https://github.com/GordonThelander/hubitat-automation-map/blob/dev/tools/webcore-investigation/wc-local-variable-extract.groovy).

## 8. Read and write direction

Direction comes from position, never from the name.

| Position | Direction |
| --- | --- |
| Variable operand (`t: 'x'`) in a condition, parameter or loop bound | Read |
| Expression variable node (`t: 'variable'` or `t: 'device'` with `x`) | Read |
| Variable inside a device list `d` | Read |
| First parameter of a `setVariable` task | Write, and not a read |
| Counter `x` of `for` or `each` | Write |
| Physical operand capture targets `dm` and `dn` | Write |
| First argument of expression `setVariable()` that is one string literal | Write |
| First argument of expression `setVariable()` built dynamically | Unresolved write target |

Directions accumulate per variable.

Implementation: [`snippets/contracts/03_variable_namespaces_and_roles.groovy`](snippets/contracts/03_variable_namespaces_and_roles.groovy).

## 9. Hub Variables

`@@name` maps to a Hubitat Hub Variable. Resolve names against the hub's Hub Variable inventory; a name
absent from a complete inventory is an unresolved reference, not a new variable. Values are never read.

## 10. webCoRE globals

`@name` globals are distinct from Hub Variables. The parent stores them under hashed names that are not
reconciled to stable identities. Record `@name` references; do not map them to a stored global.
