# Transactional Bounded-Async Discovery

Transactional Bounded-Async Discovery is a scanning pattern for Hubitat applications that need to collect many independent endpoint responses quickly without allowing concurrent callbacks to corrupt durable application state.

The method combines:

- a bounded asynchronous worker pool;
- per-attempt claims and ownership tokens;
- exact request accounting;
- bounded retry and missing-callback recovery;
- scan-local concurrent accumulation;
- invariant-checked publication from a separate scheduled execution.

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

That design is straightforward but spends most of its elapsed time waiting for independent loopback HTTP requests. It can also repeat the same work—for example, fetching capabilities separately for many devices that share one driver.

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

`asynchttpGet` can throw before a request is accepted—for example, while coercing an invalid parameter. Every dispatch is therefore wrapped in `try/catch`.

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
- establish that every undocumented Hubitat endpoint is stable across firmware versions.

Production users of the pattern should also compare complete normalized results against a serial implementation, observe status beyond the watchdog horizon, and measure resource use on representative hubs.
