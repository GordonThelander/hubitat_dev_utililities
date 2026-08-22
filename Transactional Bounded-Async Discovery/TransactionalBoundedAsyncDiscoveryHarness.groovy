/*
 * Async Dispatch Test - ISOLATED regression harness.
 *
 * Harness revision: 1.0 (curated 2026-08-22)
 * Documentation: README.md in the same repository folder.
 *
 * Purpose: test the hardened concurrent-dispatch design (try/catch rollback
 * around asynchttpGet, per-item claim/attempt-token tracking, exactly-once
 * CAS-guarded finalization, full completion invariants, and a claim-reaper
 * for accepted-but-never-answered dispatches) against REAL platform behavior
 * - a real synchronous-throw trigger, a real timeout, a genuinely missing
 * callback, and real successes - rather than reasoning about it abstractly.
 *
 * Standalone and isolated. No shared code or state, and it does not touch
 * real device/app data. Exists purely to answer:
 * does asynchttpGet throw synchronously under a bad param, and does the
 * rollback/claim/finalize design handle that correctly, a genuine timeout,
 * and a stale/duplicate callback correctly, all at once, under real
 * concurrency.
 *
 * Retain this source locally for regression testing, but remove each temporary
 * installed app and Apps Code entry from the hub after an authorized run.
 * This is diagnostic code, not a production discovery implementation.
 */
import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final String LOOPBACK_BASE = 'http://127.0.0.1:8080'
@Field static final int MAX_INFLIGHT = 8
@Field static final int ATTEMPT_CAP = 2
// Two full reap cycles (deadline + poll slop) must fit comfortably inside
// this, or the diagnostic watchdog can fire before the reaper finishes its
// own recovery - which would misreport a working recovery path as a stall.
@Field static final int WATCHDOG_SEC = 60
// Must exceed the "good" item's own 10s HTTP timeout, not just the timing
// expected in normal operation - a deadline shorter than a real request's
// own timeout makes premature reaping possible by design.
@Field static final long REAP_DEADLINE_MS = 15000
@Field static final int REAP_INTERVAL_SEC = 3
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> TESTS = new ConcurrentHashMap<>()

definition(
    name: "Transactional Bounded-Async Discovery Harness",
    namespace: "scratch",
    author: "Gordon Thelander",
    description: "Isolated regression harness for concurrent asynchttpGet dispatch, rollback, retry, reaping, and finalization semantics. Not for general use.",
    category: "Utility",
    oauth: true,
    iconUrl: "", iconX2Url: "", iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    if (!state.accessToken) {
        try { createAccessToken() } catch (Exception ignored) {}
    }
    dynamicPage(name: "mainPage", title: "Async Dispatch Test", install: true, uninstall: true) {
        section {
            paragraph "Isolated regression harness for transactional bounded-async discovery mechanics. Run one test at a time and use the API endpoints below to drive it."
            if (state.accessToken) {
                paragraph "Start: <a href='${getLocalURL('test/start')}' target='_blank'>${getLocalURL('test/start')}</a>"
                paragraph "Status: <a href='${getLocalURL('test/status')}' target='_blank'>${getLocalURL('test/status')}</a> (append ?testId=...)"
            }
        }
    }
}

String getLocalURL(String path) {
    return "${fullLocalApiServerUrl}/${path}?access_token=${state.accessToken}"
}

void installed() { initialize() }
void updated() { unsubscribe(); initialize() }
void initialize() { }

mappings {
    path('/test/start')  { action: [ GET: 'apiTestStart' ] }
    path('/test/status') { action: [ GET: 'apiTestStatus' ] }
}

// ===== Test orchestration =====

Map apiTestStart() {
    String testId = "dt-${now()}-${(int)(Math.random() * 9999)}"
    ConcurrentHashMap t = new ConcurrentHashMap()

    // 20 "good" items hit a real, fast, harmless endpoint. 5 "throw" items
    // carry a deliberately invalid param (a non-numeric timeout) chosen to
    // trigger a Groovy type-coercion error inside asynchttpGet's own
    // argument handling, before any network I/O - a safe, reliable way to
    // force a genuine synchronous exception without touching URI parsing or
    // networking edge cases. 5 "timeout" items target a non-routable
    // private address with a short timeout, forcing a real async timeout
    // callback rather than a synchronous one. 5 "missing" items deliberately
    // never call asynchttpGet at all - the claim is created as normal but no
    // request is ever made, so no callback can ever arrive. This is the one
    // failure mode nothing else in the harness produces: an accepted-looking
    // dispatch that just goes silent. Only claimReaper() can resolve these.
    List items = []
    (1..20).each { items << [id: "good-${it}", kind: 'good'] }
    (1..5).each  { items << [id: "throw-${it}", kind: 'throw'] }
    (1..5).each  { items << [id: "timeout-${it}", kind: 'timeout'] }
    (1..5).each  { items << [id: "missing-${it}", kind: 'missing'] }

    t.total = items.size()
    t.pending = new ConcurrentLinkedQueue(items)
    t.claims = new ConcurrentHashMap()
    t.done = new ConcurrentHashMap()
    t.inFlight = new AtomicInteger(0)
    t.processed = new AtomicInteger(0)
    t.finalizeGuard = new AtomicInteger(0)
    t.tokenSeq = new AtomicInteger(0)
    t.events = new ConcurrentLinkedQueue()
    t.startedAt = now()

    TESTS[testId] = t
    // Single-test-at-a-time harness: testWatchdog and claimReaper are both
    // unscheduled/rescheduled globally by handler name, and runIn() with the
    // same handler name replaces rather than stacks prior schedules, so this
    // is only safe because exactly one test runs at a time. Not fixed for
    // concurrent tests - out of scope for this isolated harness.
    runIn(WATCHDOG_SEC, 'testWatchdog', [data: [testId: testId]])
    runIn(REAP_INTERVAL_SEC, 'claimReaper', [data: [testId: testId]])

    logInfo(t, "TEST START testId=${testId} total=${items.size()}")
    refillPipeline(testId)

    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson([testId: testId, total: items.size()]))
}

void logInfo(ConcurrentHashMap t, String msg) {
    String line = "${new Date().format('HH:mm:ss.SSS')} ${msg}"
    (t.events as ConcurrentLinkedQueue) << line
    log.info "[dispatchtest] ${msg}"
}

void refillPipeline(String testId) {
    // Iterative, not recursive: dispatchOne() returns true whenever it made
    // progress (a successful dispatch, or a rollback+requeue/terminal-fail
    // after a synchronous throw), so looping here bounds the work to the
    // pending queue's size instead of growing the call stack on repeated
    // synchronous failures.
    while (dispatchOne(testId)) { /* keep refilling */ }
}

boolean dispatchOne(String testId) {
    ConcurrentHashMap t = TESTS[testId]
    if (t == null) return false

    AtomicInteger inFlight = t.inFlight as AtomicInteger
    while (true) {
        int n = inFlight.get()
        if (n >= MAX_INFLIGHT) return false
        if (inFlight.compareAndSet(n, n + 1)) break
    }

    Map item = (t.pending as ConcurrentLinkedQueue).poll()
    if (item == null) {
        inFlight.decrementAndGet()
        return false
    }

    String itemId = item.id as String
    String kind = item.kind as String
    // Attempt count travels WITH the requeued item, not via the claim map -
    // the claim is deleted on every failure path before requeue, so deriving
    // the count from the claim would silently reset it to 1 forever.
    int attemptCount = ((item.attemptCount ?: 0) as Integer) + 1
    String attemptToken = "tok-${(t.tokenSeq as AtomicInteger).incrementAndGet()}"
    Map myClaim = [attemptToken: attemptToken, dispatchedAt: now(), attemptCount: attemptCount, kind: kind]
    (t.claims as ConcurrentHashMap)[itemId] = myClaim

    logInfo(t, "dispatch-started id=${itemId} kind=${kind} token=${attemptToken} attempt=${attemptCount}")

    if (kind == 'missing') {
        // Deliberately never call asynchttpGet - the claim above is the only
        // trace this attempt leaves. Nothing will ever resolve it except
        // claimReaper() noticing it has aged past REAP_DEADLINE_MS.
        logInfo(t, "dispatch-accepted-no-callback-will-ever-arrive id=${itemId} token=${attemptToken}")
        return true
    }

    try {
        Map params
        if (kind == 'good') {
            params = [uri: "${LOOPBACK_BASE}/hub/cpuInfo", timeout: 10]
        } else if (kind == 'throw') {
            // Deliberately wrong type for timeout - expected to throw inside
            // asynchttpGet's own argument handling before any request is sent.
            params = [uri: "${LOOPBACK_BASE}/hub/cpuInfo", timeout: "not-a-number"]
        } else {
            // RFC 5737 TEST-NET-1 - guaranteed non-routable, safe to target,
            // will not reach any real host. Short timeout forces a genuine
            // async timeout callback rather than hanging the test.
            params = [uri: "http://192.0.2.1/unreachable", timeout: 3]
        }
        asynchttpGet('dispatchCb', params, [testId: testId, itemId: itemId, attemptToken: attemptToken, kind: kind])
        logInfo(t, "dispatch-accepted id=${itemId} token=${attemptToken}")
        return true
    } catch (Exception ex) {
        logInfo(t, "dispatch-threw id=${itemId} token=${attemptToken} error=${ex.message}")
        // ROLLBACK: release the slot and the claim this attempt made. Ownership
        // is proven the same way dispatchCb/reapOne prove it - only remove if
        // the map still holds exactly the claim this execution installed above.
        // Nothing else could plausibly have touched it yet (asynchttpGet threw
        // before any request left this execution), but the conditional remove
        // costs nothing and keeps all three retirement paths consistent.
        boolean owned = (t.claims as ConcurrentHashMap).remove(itemId, myClaim)
        if (!owned) {
            logInfo(t, "rollback-lost-race id=${itemId} token=${attemptToken} - claim already retired elsewhere")
            return true
        }
        inFlight.decrementAndGet()
        if (attemptCount < ATTEMPT_CAP) {
            (t.pending as ConcurrentLinkedQueue) << [id: itemId, kind: kind, attemptCount: attemptCount]
            logInfo(t, "requeued-after-throw id=${itemId} attempt=${attemptCount}")
        } else {
            (t.done as ConcurrentHashMap)[itemId] = "failed: dispatch threw ${attemptCount}x: ${ex.message}"
            (t.processed as AtomicInteger).incrementAndGet()
            logInfo(t, "terminal-fail-after-throw id=${itemId}")
        }
        maybeFinalize(testId)
        return true   // made progress; refillPipeline's loop will try again
    }
}

void dispatchCb(resp, data) {
    String testId = data.testId as String
    ConcurrentHashMap t = TESTS[testId]
    if (t == null) return   // stale/finalized test - discard

    String itemId = data.itemId as String
    String attemptToken = data.attemptToken as String
    String kind = data.kind as String
    Map claim = (t.claims as ConcurrentHashMap)[itemId] as Map

    if (claim == null || claim.attemptToken != attemptToken) {
        logInfo(t, "callback-stale-or-duplicate id=${itemId} token=${attemptToken} currentToken=${claim?.attemptToken}")
        return
    }

    // Ownership: only the execution whose conditional remove actually wins
    // may release the reservation or resolve/requeue the item. The token
    // check above rules out a callback for an OLD attempt; this rules out
    // losing a race against claimReaper() retiring THIS exact attempt
    // between that check and this point.
    boolean owned = (t.claims as ConcurrentHashMap).remove(itemId, claim)
    if (!owned) {
        logInfo(t, "callback-lost-race id=${itemId} token=${attemptToken} - claim already retired elsewhere")
        return
    }

    boolean ok = false
    String detail
    try {
        // .errorMessage throws when hasError() is false, so read hasError()
        // once and only touch errorMessage behind it.
        boolean hasError = resp != null && resp.hasError()
        ok = resp != null && !hasError && resp.status == 200
        String errorMessage = hasError ? "${resp.errorMessage}" : null
        detail = "status=${resp?.status} hasError=${hasError} errorMsg=${errorMessage}"
    } catch (Exception ex) {
        detail = "callback-inspection-error: ${ex.message}"
    }
    logInfo(t, "callback-entered id=${itemId} token=${attemptToken} kind=${kind} ${detail}")

    int attemptCount = claim.attemptCount as Integer

    if (ok) {
        (t.done as ConcurrentHashMap)[itemId] = "ok (attempt ${attemptCount})"
        (t.processed as AtomicInteger).incrementAndGet()
        logInfo(t, "callback-accepted id=${itemId}")
    } else if (attemptCount < ATTEMPT_CAP) {
        (t.pending as ConcurrentLinkedQueue) << [id: itemId, kind: kind, attemptCount: attemptCount]
        logInfo(t, "callback-retry id=${itemId} attempt=${attemptCount}")
    } else {
        (t.done as ConcurrentHashMap)[itemId] = "failed after ${attemptCount} attempts: ${detail}"
        (t.processed as AtomicInteger).incrementAndGet()
        logInfo(t, "callback-terminal-fail id=${itemId}")
    }

    (t.inFlight as AtomicInteger).decrementAndGet()

    refillPipeline(testId)
    maybeFinalize(testId)
}

void maybeFinalize(String testId) {
    ConcurrentHashMap t = TESTS[testId]
    if (t == null) return
    int inFlight = (t.inFlight as AtomicInteger).get()
    int pending = (t.pending as ConcurrentLinkedQueue).size()
    int claimsOutstanding = (t.claims as Map).size()
    int processed = (t.processed as AtomicInteger).get()
    int doneSize = (t.done as Map).size()
    int total = t.total as Integer

    if (pending == 0 && inFlight == 0 && claimsOutstanding == 0 && processed == total && doneSize == total) {
        if ((t.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) {
            unschedule('testWatchdog')
            unschedule('claimReaper')
            logInfo(t, "FINALIZE testId=${testId} done=${doneSize} processed=${processed} total=${total} elapsedMs=${now() - (t.startedAt as Long)}")
        }
        return
    }

    if (processed > total || doneSize > total || pending < 0 || inFlight < 0) {
        logInfo(t, "INVARIANT VIOLATION testId=${testId} pending=${pending} inFlight=${inFlight} claims=${claimsOutstanding} processed=${processed} done=${doneSize} total=${total}")
    }
}

void testWatchdog(data) {
    String testId = data?.testId as String
    ConcurrentHashMap t = TESTS[testId]
    if (t == null) return
    logInfo(t, "WATCHDOG FIRED testId=${testId} pending=${(t.pending as ConcurrentLinkedQueue).size()} inFlight=${(t.inFlight as AtomicInteger).get()} processed=${(t.processed as AtomicInteger).get()} total=${t.total} claimsOutstanding=${(t.claims as Map).size()}")
}

// Active recovery mechanism under test: detects claims older than
// REAP_DEADLINE_MS, atomically retires the attempt token,
// releases the reservation exactly once, and retries with a new token up to
// ATTEMPT_CAP. Hubitat callbacks can genuinely overlap with this scheduled
// execution - that is the entire reason the rest of this app uses
// ConcurrentHashMap/AtomicInteger/CAS in the first place, so this reaper and
// dispatchCb both prove exclusive ownership via conditional removal
// (Map.remove(key, value)) before either one is allowed to touch inFlight or
// resolve/requeue the item. Whichever loses that race backs off silently.
void claimReaper(data) {
    String testId = data?.testId as String
    ConcurrentHashMap t = TESTS[testId]
    if (t == null) return
    if ((t.finalizeGuard as AtomicInteger).get() == 1) return   // done - stop rescheduling

    long nowMs = now()
    Map claims = t.claims as ConcurrentHashMap
    // Snapshot the claim VALUE at scan time, not just the id - reapOne must
    // retire exactly this attempt, never whatever attempt currently occupies
    // that id by the time it actually runs (a newer, non-stale attempt could
    // have replaced it in between).
    List<Map> staleCandidates = []
    claims.each { itemId, claim ->
        long dispatchedAt = (claim as Map).dispatchedAt as Long
        if (nowMs - dispatchedAt >= REAP_DEADLINE_MS) {
            staleCandidates << [itemId: itemId as String, claim: claim as Map]
        }
    }
    staleCandidates.each { c -> reapOne(testId, c.itemId as String, c.claim as Map) }

    // Recheck rather than trust the guard read at the top of this execution -
    // reapOne() above can itself finalize the test partway through this loop.
    ConcurrentHashMap t2 = TESTS[testId]
    if (t2 != null && (t2.finalizeGuard as AtomicInteger).get() != 1) {
        runIn(REAP_INTERVAL_SEC, 'claimReaper', [data: [testId: testId]])
    }
}

void reapOne(String testId, String itemId, Map candidateClaim) {
    ConcurrentHashMap t = TESTS[testId]
    if (t == null) return

    // Re-verify staleness against the snapshot, then retire only that EXACT
    // claim object. If it resolved or was replaced since the scan, this
    // conditional remove fails and we back off instead of retiring a live,
    // non-stale attempt that happens to share the same item id.
    long ageMs = now() - (candidateClaim.dispatchedAt as Long)
    if (ageMs < REAP_DEADLINE_MS) return

    Map claims = t.claims as ConcurrentHashMap
    boolean owned = claims.remove(itemId, candidateClaim)
    if (!owned) {
        logInfo(t, "reap-lost-race id=${itemId} token=${candidateClaim.attemptToken} - claim already retired or replaced")
        return
    }

    int attemptCount = candidateClaim.attemptCount as Integer
    String kind = candidateClaim.kind as String
    logInfo(t, "claim-reaped id=${itemId} token=${candidateClaim.attemptToken} attempt=${attemptCount} ageMs=${ageMs}")

    (t.inFlight as AtomicInteger).decrementAndGet()

    if (attemptCount < ATTEMPT_CAP) {
        (t.pending as ConcurrentLinkedQueue) << [id: itemId, kind: kind, attemptCount: attemptCount]
        logInfo(t, "reap-retry id=${itemId} attempt=${attemptCount}")
    } else {
        (t.done as ConcurrentHashMap)[itemId] = "failed: reaped after ${attemptCount} attempts, no callback within ${REAP_DEADLINE_MS}ms"
        (t.processed as AtomicInteger).incrementAndGet()
        logInfo(t, "reap-terminal-fail id=${itemId}")
    }

    refillPipeline(testId)
    maybeFinalize(testId)
}

Map apiTestStatus() {
    String testId = params.testId as String
    ConcurrentHashMap t = testId ? TESTS[testId] : null
    if (t == null) {
        return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson([found: false]))
    }
    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson([
        found: true,
        total: t.total,
        pending: (t.pending as ConcurrentLinkedQueue).size(),
        inFlight: (t.inFlight as AtomicInteger).get(),
        processed: (t.processed as AtomicInteger).get(),
        claimsOutstanding: (t.claims as Map).size(),
        doneCount: (t.done as Map).size(),
        doneDetail: t.done,
        finalized: (t.finalizeGuard as AtomicInteger).get() == 1,
        events: (t.events as ConcurrentLinkedQueue).toList()
    ]))
}
