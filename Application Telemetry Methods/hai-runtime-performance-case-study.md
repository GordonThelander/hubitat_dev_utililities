# HAI Runtime latency and state: a measured case study

**Date:** 23 September 2026 (AWST)  
**System:** Hubitat Automation Intelligence (HAI) on a Dev hub  
**Decision:** The owner accepted the measured latency and a capped rule count for alpha. The release cap still needs enforcement. This does not authorize production deployment, relax device safeguards, or establish a safe large-scale parent-state bound.

## Executive finding

HAI's original targets were a Runtime-accepted receipt within one second and editor-visible confirmation of a running rule within five seconds. The current Dev architecture cannot meet the receipt target through its existing location-event return path. On a quiet measured run, the Runtime-to-Engine summary event took **3.020 seconds** after the Runtime sent it. The Engine's quick response to the original editor request only means it sent the request; it is not a Runtime acceptance.

The final retained Dev build uses one activation review for auto-approved rules and a compact summary cache. With 40 existing child apps retained, three comparable warm updates to the same safe virtual rule measured:

| Phase from signed editor `sentAt` | Three runs (ms) | Median (ms) |
| --- | --- | ---: |
| Runtime writes `runtimeLastSubmit` | 3,217 / 3,289 / 3,067 | 3,217 |
| Runtime registry records `active` | 4,688 / 4,749 / 4,600 | 4,688 |
| Engine stores the Runtime summary | 6,295 / 6,307 / 6,194 | 6,295 |

The first row is an internal processing timestamp, **not** an editor-visible receipt. The last row is the earliest Engine-side confirmation; the editor's 1.5-second polling interval can add more time. The rule was active in all three runs, all 40 child apps remained, and the sampled Runtime state log and recent hub logs showed no excessive-hub-load error. These observations support alpha use under the owner's revised latency decision. They do not prove the old targets or performance on another hub.

## What was measured

The path is `editor → HAI Engine → signed location event → HAI Runtime parent → rule child → child commit event → Runtime registry → Runtime summary event → HAI Engine → editor poll`. Each boundary has a different meaning:

1. **Engine send response:** the Engine signed and emitted the request. Runtime may not have handled it yet.
2. **Runtime acceptance:** Runtime checked the signature, age, and replay and durably recorded the submission. `runtimeLastSubmit.at` is later than initial handler entry because the current code also keeps and stages the candidate before assigning it.
3. **Child commit:** the rule child saved the new version. Its `rt.committed.at` is a durable running point for that child.
4. **Parent confirmation:** Runtime validated the child's claim, challenge, epoch and hash, then moved its registry entry to `active`.
5. **Engine confirmation:** Engine received and stored the parent's summary. Only this, followed by an editor poll, can drive the current editor's “Running on the hub” message.

A quiet transport discriminator in HAI-915 measured **3.020 seconds** from Runtime `sendLocationEvent` to Engine `runtimeSummaryAt`, versus 3.169 seconds while the Engine was polled twice per second. That one comparison rules out aggressive polling as the main cause of the return delay, but does not separate Hubitat event delivery from Engine event-handler state persistence. [Hubitat documents](https://docs2.hubitat.com/developer/app/definition) that a `singleThreaded` app loads and saves its state around each top-level method and serializes calls to that app. This explains why queuing and state size are plausible contributors; it does not assign milliseconds to either one.

## The useful changes and the dead ends

The earlier full summary rebuild read every child on the minute pass and sometimes took several seconds. The compact cache now retains only each child's small final summary row and reads a child only when its rule may have changed. The first cache fill after deployment can still be cold. This removed recurring work; it did not solve the submission latency by itself. The cache contains no child document, trace, ledger or older versions.

Consolidating three parent-state writes in the submission path changed the comparable warm median from roughly **6.9 to 6.0 seconds** for Runtime receipt and **9.4 to 8.5 seconds** for running (HAI-877 versus HAI-893). This is an observed before/after association, not a calibrated cost per write: the same patch removed some other work. Later work moved auto activation into the submission execution, skipped presentation-only review on the Dev auto-approved path, and finally performed a **single** claim-time review. The person-driven approval path and the child's hash, epoch and allow-list checks remain.

Several attractive explanations did not survive measurement or source review:

- **Sunrise/sunset and Rule Machine lookups:** the old review fetched sun times for three days and the RM list even for a simple virtual rule. Making these lookups conditional was correct for scope, but its three warm runs showed no meaningful latency gain (HAI-903). It was not the main bottleneck.
- **A full-rule submission sweep:** the compact-cache candidate eliminated it. Later latency remained with only the changed child read. Do not diagnose each slow submission as a 40-child sweep.
- **The 416 to 420 KiB source budget:** this was a project deployment guard, not an observed Hubitat hard limit. The retained Runtime build is 431,865 assembled bytes under the revised 440 KiB Dev guard. Source size and application state size are different measurements.
- **Direct HTTP into the large Runtime parent:** one warm prototype returned genuine Runtime-accepted HTTP 202 in **1,344 ms**, then took about **7,078 ms more** from acceptance to active after an asynchronous worker handoff. It missed both original gates. The prototype was restored, not retained.
- **Cached acknowledgement before child refresh:** a later one-line Dev experiment kept the immediate summary cache-only. Its two valid updates measured 4,010/3,261 ms to internal submission record, 5,496/4,608 ms to parent active, and 7,277/6,740 ms to Engine summary. It provided no useful improvement over the retained build and was restored. One invalid test request sent command arguments as a string rather than a list; it was excluded and did not activate a rule.
- **Increasing timeouts, deleting fixtures, or repeating full suites:** none addresses the measured event route or state ownership. The final experiment ran the targeted Runtime suite once (163 passing checks) and reused the existing virtual rule.

## State growth is a separate alpha limitation

At 40 children, a sampled Runtime parent state was **122,879 bytes**. Its largest recorded keys were approximately `rtRegistry` 47,879 bytes, `rtVersions` 36,930 bytes, `rtSumRows` 15,154 bytes, candidates 7,294 bytes, and watchdog 6,949 bytes. The registry holds current full rule documents, and version history holds additional full documents. The child also holds its executable document. This duplicates data across owners.

The code caps the Dev registry at 40 rules (the later non-Dev cap is 32), candidates at 12, each candidate document at 16 KiB, and parent version history at five versions per rule. These caps make the count finite, but the crude document-only upper projection for 32 rules is roughly `32 × (current + 5 versions) × 16 KiB = 3 MiB`, before candidates and other fields. The measured 123 KB is far below that projection. **No acceptable parent-state byte ceiling at maximum document size or migration scale was demonstrated.** Alpha should retain the present rule-count limits and monitor state size; a larger Rule Machine migration needs a separate state-ownership design and load trial.

A candidate design, not an implemented result, is a compact parent index of rule identity, state, epoch, hash, child ID and the small cross-rule interaction facts that validation needs, with full document history owned per rule. Migration must copy, verify and only then remove old parent data, while preserving restart and rollback behavior. Merely compressing the parent or moving an unbounded cache does not establish the needed bound.

## Reusable diagnostic method

1. Use one existing safe virtual rule and retain the installed children. Change one version field per update; do not create and delete test rules to get fresh epochs.
2. Separate the post-deployment cold update from at least three comparable warm updates. Record the exact deployed source hash and revision, rule epoch, child count, and hub-load errors.
3. Correlate the signed `sentAt` with Runtime handler entry, `runtimeLastSubmit.at`, child `rt.committed.at`, parent registry `updatedAt`, Runtime summary send, Engine `runtimeSummaryAt`, and editor polling. Label each timestamp by what it actually proves.
4. Instrument only the unresolved span. A broad “summary took” timer can include a child read, label/link updates, composition and state writes; it cannot by itself identify one of them. Remove diagnostic-only markers from a retained build.
5. Avoid extra polling during timing runs. A `singleThreaded` app may queue a poll ahead of the handler being measured. Read state once after the event settles where possible.
6. Keep observations and causal claims distinct. A count of `atomicState` writes correlated with time is not an isolated per-write benchmark. A fast Engine send response is not Runtime acceptance. A child's committed timestamp is not the parent's registry confirmation.
7. Stop an experiment when a valid warm result and its follow-up show no material gain. Restore the exact verified Dev source and keep the failed result in the record.

## Alpha disposition and follow-up

The owner accepted the current latency and a capped rule count for alpha on 23 September 2026, with parent-state ownership redesign deferred. The installed Dev build enforces 40 rules; the planned 32-rule release cap is not yet an installed production control and must be enforced and verified during release preparation. The retained Dev source is SHA-256 `13b8eb2ca4a3a1bffc45e2172ae89724e928f88380b30f3d88558710110ae93e`, restored and verified at Apps Code revision 127. The three comparable warm results above came from the same source at revision 125. This is a Dev source result, **not** a production promotion or a complete release-readiness claim. The independent editor-to-Runtime functional check on the existing virtual rule had passed earlier at epoch 9; the later three warm version updates reached `active` and kept all 40 children.

If the original speed targets return, a small, dedicated Runtime-owned intake with a durable signed receipt is the credible path to test for sub-second acceptance; the heavy parent and a second scheduled worker did not meet it. A faster editor confirmation may use a bounded, authenticated child-commit signal, but it must not claim running before the child's state is durable or lose the parent's claim validation. Both are architectural candidates, not measured solutions. State ownership and a realistic migration-load ceiling should be addressed before promising hundreds of rules.

**Evidence trail:** HAI queue messages 841-920, especially HAI-850 (summary cache), HAI-877 and HAI-893 (write reduction), HAI-903 (lazy lookup result), HAI-911 and HAI-915 (phase and transport timing), HAI-919 (single-review candidate), and the 23 September Dev rev125-127 readbacks. Timings were taken on one hub and can vary with hub load. No production deployment was performed for this study.
