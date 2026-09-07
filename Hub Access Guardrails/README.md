# Hub Access Guardrails

A tiering model for scoping what an AI agent may do to a home-automation hub (or any system
controlling real-world devices) without asking first, versus what needs confirmation each time,
versus what needs a standing, explicit, one-off authorization. Drafted as a second opinion on this
project's own MCP tool access to a live Hubitat hub, kept here generalized as a reusable pattern for
future projects rather than tied to one server's tool names.

**The problem this solves:** a hub's MCP surface typically exposes reads, ordinary writes, and
destructive/hub-wide operations through tools that look equally callable to an agent - nothing in
the tool list itself distinguishes "read a device's state" from "reset the radio" or "restore the
whole hub from backup." A blanket policy is wrong in both directions: requiring confirmation for
every read makes the agent unusable, and allowing every write without distinction risks exactly the
class of action that is hard or impossible to undo. This model sorts by actual consequence and blast
radius, not by which tool happens to expose the capability.

## The four tiers

### Tier 0 - read-only, no confirmation

Inventory, state, metadata, source inspection, logs, health, rule inspection, search, and dry-run
validation. Free rein - an agent that has to ask before reading anything is not worth deploying.

**Exception:** a nominally read-labelled operation that causes side-effect activity - recording a
snapshot, running a speed test or traceroute, blinking an identify LED - is an action wearing a read
tool's name, and should require an explicit user request even though it looks like a Tier 0 call.

### Tier 1 - reversible ordinary writes, explicit task intent

Non-sensitive device commands, ordinary variable/rule/dashboard/room edits, and similarly reversible
configuration changes. Allowed without per-action confirmation, gated instead on process discipline:
an exact target (never an inferred fuzzy match), any revision/version guard the tool offers actually
used, and the result read back afterward to confirm it landed.

### Tier 2 - sensitive or destructive writes, action-time confirmation

Locks, garage/cover movement, valves, HSM, sirens, device replacement or deletion, rule/app/driver/
library deletion, room deletion, backup restore, radio enable/disable or channel changes, and any
change that can strand a device or break a reference other things depend on. Before confirming:
identify the exact target and its consequences, inspect what else depends on it, ensure a recent
backup exists where applicable - then confirm and verify the result afterward.

### Tier 3 - hub-wide or availability-threatening, explicit one-off authorization

Firmware installation, hub reboot or shutdown, network changes, whole-hub restore, radio reset or
firmware flash, cloud-service disablement, and anything that would widen the agent's own access
(an MCP self-update, a settings change, bypassing a device allowlist). Never inferred from a broad
"do some maintenance" request - each one needs the exact operation named, a backup under 24 hours,
the stated downtime/blast radius, confirmation immediately before execution, and post-operation
verification.

## Separate source/publication gates

Writing and shipping code is a different axis from operating the hub, and the two should not share
one permission:

- **App/driver/library code writes** to the hub follow their own review discipline - a reviewed
  source, an exact target, an optimistic-lock/version guard where the platform offers one, a backup,
  and verification after the write.
- **Hub deployment, local commit, a GitHub push, branch promotion, a release/tag, and package-
  manager publication are five separate permissions.** A tool that is technically capable of doing
  all five in one call does not mean authorization for one implies authorization for the rest -
  each still needs its own explicit go-ahead.

## When to use this

Any time an agent gets direct, structured (MCP or equivalent) write access to a system where some
actions are trivially reversible and others are not - a smart-home hub, but the same shape applies
to infrastructure-as-code, a build/release pipeline, or anything else where "the tool can call it"
and "a human meant to authorize it right now" are not the same fact. Sort the real tool surface into
these four tiers once, rather than deciding trust per call in the moment.
