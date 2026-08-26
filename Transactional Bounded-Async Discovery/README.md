# Transactional Bounded-Async Discovery

Transactional Bounded-Async Discovery is a scanning pattern for Hubitat applications that need to collect many independent endpoint responses quickly without allowing concurrent callbacks to corrupt durable application state.

The method combines:

- a bounded asynchronous worker pool;
- per-attempt claims and ownership tokens;
- a generation-level single-flight lock;
- exact request accounting;
- bounded retry and missing-callback recovery;
- scan-local concurrent accumulation;
- invariant-checked publication from a separate scheduled execution;
- fenced terminal publication that keeps newer scans isolated from late work.

The included `TransactionalBoundedAsyncDiscoveryHarness.groovy` is an isolated Hubitat test application for the concurrency and recovery mechanics. It does not enumerate real devices or installed applications.

## Origin

The bounded-async architecture, and the core insight behind its state-isolation design, come
from hubitrep's `HubDiagnostics` app (`github.com/hubitrep/hubitat`). A data-integrity bug in an
early implementation traced back to hubitrep's own documented fix for the same platform behavior:
concurrent `asynchttpGet` callbacks racing on Hubitat `state`. Their fix keeps real results out of
`state` entirely, in a disposable field instead - acceptable for an on-demand audit tool whose
data does not need to survive a reboot.

This pattern extends that insight for applications where the result must be durable: per-attempt
claims, exclusive ownership, missing-callback recovery, and invariant-checked scheduled
publication replace the disposable-field approach with one that can't lose or corrupt a result
that has to persist.

## Previous serial method

The previous discovery method was a blocking, serial crawl:

1. enumerate the hub inventory;
2. fetch detail for one device;
3. wait for that request to complete;
4. repeat for every device;
5. fetch and parse one installed application at a time;
6. build the result after the final request.

That design is straightforward but spends most of its elapsed time waiting for independent loopback HTTP requests. It can also repeat the same work, for example fetching capabilities separately for many devices that share one driver.

The updated method first reduces the amount of work:

- one bulk `/hub2/devicesList` request supplies device IDs, labels, rooms, driver names, and driver identifiers;
- devices are grouped by `deviceTypeId`;
- capabilities are fetched once per driver group and applied to every device in that group;
- installed-application detail requests remain independent and can run concurrently.

On the measured hub, 194 devices collapsed to 34 driver groups.

## Processing model

```text
Bulk inventory
      |
      v
Pending queue -----> bounded dispatcher (maximum 8 in flight)
                          |
                          v
                 claim + attempt token
                          |
                +---------+----------+
                |                    |
                v                    v
            callback             claim reaper
                |                    |
                +---- atomic ownership ----+
                             |
                             v
                 scan-local accumulator
                             |
                  exact invariants satisfied
                             |
                             v
                   scheduled finalizer
                             |
                             v
                  one durable publication
```

### 1. Bounded dispatch

Work begins in a `ConcurrentLinkedQueue`. Before dispatching, the scanner atomically reserves an `inFlight` slot. No more than eight requests are active at once.

The dispatcher then removes one item and creates a claim containing:

```text
item ID
attempt token
dispatch timestamp
attempt count
```

The claim exists before `asynchttpGet` is called, so every accepted or failed dispatch has an accounting record.

### 2. Synchronous-dispatch rollback

`asynchttpGet` can throw before a request is accepted, for example while coercing an invalid parameter. Every dispatch is therefore wrapped in `try/catch`.

On a synchronous exception, the dispatcher atomically removes its exact claim, releases the reserved slot, and either requeues the work or records terminal failure. Pipeline refill is iterative rather than recursive, preventing repeated dispatch rejection from growing the call stack.

### 3. Exclusive ownership

A callback and the missing-callback reaper can overlap. Only one execution may retire an attempt.

Ownership is established with conditional removal:

```groovy
claims.remove(itemId, exactClaimObject)
```

Only the execution for which this returns `true` may decrement `inFlight`, retry the item, or record its result. Attempt tokens also prevent a late callback from an older attempt from resolving a newer retry.

### 4. Missing-callback recovery

Accepted requests do not always guarantee a usable callback. A scheduled reaper examines claims older than a deadline that exceeds the longest request timeout plus scheduling margin.

An expired claim is retired through the same ownership operation. The work is retried up to the attempt cap and then recorded as unreadable. This ensures a missing callback cannot leave the scan permanently stranded with a nonzero `inFlight` count.

### 5. Volatile collection and durable publication

Concurrent callbacks must not update Hubitat `state` or `atomicState`. They write only to a scan-ID-specific `ConcurrentHashMap` containing queues, counters, claims, and collected results.

Completion requires exact equality, not a permissive threshold. A typical app phase requires:

```text
pending == 0
inFlight == 0
claims == 0
processed == total
results.size == total
decoded + unreadable == total
```

When the conditions first hold, a CAS guard schedules finalization exactly once. The scheduled finalizer runs in a separate Hubitat execution, rechecks every invariant, obtains a second publication guard, and replaces the durable result in one controlled execution.

If the watchdog expires before completion, the scan fails closed and does not publish partial data as a completed result.

### 6. Generation-level single-flight ownership

Per-item claims prevent a callback and reaper from retiring the same request, but they do not stop two complete scans from starting at once. A durable flag such as `state.scanRunning` is not sufficient for this purpose. Two Hubitat executions can both read the old value before either execution commits its updated state.

Use a process-local atomic map keyed by installed application ID:

```groovy
@Field static final ConcurrentHashMap<String, String> SCAN_LOCKS = new ConcurrentHashMap<>()

String generationToken = "scan-${now()}-${UUID.randomUUID()}"
if (SCAN_LOCKS.putIfAbsent("${app.id}", generationToken) != null) {
    return [acquired: false]
}
```

The value must identify one scan generation, not merely contain `true`. A boolean lock cannot distinguish a late callback from an older scan after a newer scan has acquired the same application slot.

Carry the original generation token explicitly through every phase:

- store it on each scan-local accumulator;
- pass it as a parameter when moving from device discovery to application discovery;
- include it in every `runIn()` data payload used by registry and finalization handlers;
- never let an ordinary callback or scheduled phase adopt ownership by reading the current token from shared state.

A late handler must prove that its remembered token is still current before its first durable write. Recheck after any blocking HTTP operation and immediately before final publication. A token passed to a terminal helper is not enough if the handler has already modified shared state on its way there.

The lock key is the installed application ID. Separate production and development instances therefore own separate slots and can still run simultaneously. Stagger their scheduled scans to avoid competing for hub CPU, memory, and loopback endpoints even though their correctness locks do not conflict.

### 7. Fenced terminal publication

Do not remove the generation lock and then publish state. A new scan can acquire in the gap and have its fresh state overwritten by the older scan's final writes.

Atomically replace the owner token with a timestamped finishing value:

```groovy
boolean finishGeneration(String token, String error = null, Closure publishWork = null) {
    if (token == null) return false
    String key = "${app.id}"
    String finishing = "finishing:${token}:${now()}"
    if (!SCAN_LOCKS.replace(key, token, finishing)) return false

    try {
        if (publishWork != null) publishWork()
        if (error != null) state.scanError = error
    } finally {
        state.scanRunning = false
        SCAN_LOCKS.remove(key, finishing)
    }
    return true
}
```

The atomic replacement is both an ownership check and a fence. Only the current generation can enter publication, and `putIfAbsent` continues rejecting new scans until every durable result has been written. Release is the final action and belongs in `finally`.

All terminal paths should use the same protocol, including bootstrap failures, watchdog failures, graph-build errors, and successful completion. A separate remove-then-write error path recreates the same race.

### 8. State aliasing and abandoned-generation recovery

Reading a collection from `state` into a local variable does not copy it:

```groovy
Map unsafe = state.registryMeta as Map
unsafe.state = 'FAILED'
```

That mutation may alter the state-held object before ownership has been proved. Make a defensive copy before local computation, then publish the copy only inside the finishing fence:

```groovy
Map localMeta = new LinkedHashMap((state.registryMeta ?: [:]) as Map)
```

A finishing execution can itself be killed before `finally` runs. Include a timestamp in the finishing value and let abandoned-scan recovery treat it as active only for a conservative bounded interval. Recovery may snapshot the current value because its purpose is to recover the generation that owns the slot now, rather than act on behalf of an earlier generation. Recovery must not remove the old value before writing recovery state. It should atomically replace the exact stranded value with its own recovery sentinel, write the error and running status while acquisition remains blocked, then conditionally remove the recovery sentinel last.

The atomic map is process-local and may be empty after a hub reboot or application code reload while durable `state.scanRunning` still says `true`. Startup or abandoned-scan recovery must recognize that combination. Once it has established that no scan-local accumulator or scheduled phase can still be live, it can clear the orphaned durable status without pretending that a missing process-local token proves ownership.

The same fencing rule applies throughout: stale or superseded work may compute locally, but it must not publish, clear status, release a newer lock, or adopt the newer generation's identity.

## Measured performance

The controlled comparison used a hub with 194 devices and approximately 105 installed applications.

| Method | Duration |
|---|---:|
| Serial baseline | 134.0s |
| Bounded-async run 1 | 23.5s |
| Bounded-async run 2 | 21.1s |
| Bounded-async run 3 | 22.3s |
| Bounded-async run 4 | 25.5s |
| Bounded-async average | 23.1s |

This is approximately **5.8 times faster**, or an **82.8% reduction in elapsed scan time**, against the same-day serial baseline.

All four bounded-async runs produced identical counters: 194 devices, 105 decoded applications, zero unreadable devices, zero unreadable applications, and 61 decoded rule flows. Normalized node and edge comparison matched the serial result after accounting for one application that independently disappeared from the hub's bulk application listing between measurement windows.

## Test harness

The harness creates 35 synthetic work items behind the same eight-request concurrency limit:

| Scenario | Count | Expected result |
|---|---:|---|
| Successful loopback request | 20 | Completes on attempt 1 |
| Synchronous dispatch exception | 5 | Rolls back and terminally fails after attempt 2 |
| Asynchronous timeout | 5 | Callback retry, then terminal failure after attempt 2 |
| Deliberately missing callback | 5 | Reaped, retried, then terminal failure after attempt 2 |

A successful harness run must end with:

```text
total == 35
pending == 0
inFlight == 0
claimsOutstanding == 0
processed == 35
doneCount == 35
finalized == true
```

The authoritative run completed all 35 items in 36.401 seconds, finalized once, and produced no invariant violation or watchdog event.

## Running the harness

Installing or executing the harness changes a Hubitat hub and should be done only on an authorized development system.

1. Create a temporary Apps Code entry using `TransactionalBoundedAsyncDiscoveryHarness.groovy`.
2. Install one instance and enable OAuth.
3. Open the application page and use its generated **Start** link once.
4. Copy the returned `testId`.
5. Open the generated **Status** URL and append `&testId=<testId>`.
6. Poll until `finalized` is `true` or the watchdog fires.
7. Save the status JSON and relevant hub logs.
8. Remove the temporary installed instance and Apps Code entry.

Do not run two tests simultaneously. The harness schedules its watchdog and reaper by handler name, so a second run can replace the active run's scheduled jobs.

URLs containing `access_token` are credentials and must not be committed or shared.

## Scope and limitations

The harness validates dispatch, rollback, retry, reaping, ownership, accounting, and finalization mechanics. It does not:

- scan real devices or applications;
- measure hub CPU or peak memory pressure;
- persist its synthetic results across a reboot or code reload;
- deterministically force callback/reaper contention at the deadline boundary;
- validate generation-level single-flight ownership between two complete scans;
- validate abandoned-generation and competing-finalizer interleavings;
- establish that every undocumented Hubitat endpoint is stable across firmware versions.

Production users of the pattern should also compare complete normalized results against a serial implementation, observe status beyond the watchdog horizon, deliberately issue near-simultaneous start requests, verify that only one generation-start log is emitted, test late stale handlers against a replacement generation, and measure resource use on representative hubs.
