# Application Telemetry Methods

Two different, unrelated things both get called "telemetry" for a Hubitat app: a developer
switching on extra logging to troubleshoot their own hub, and an app reporting anonymous usage
data back to its author. This directory documents both, generalized from Automation Map's own
implementation (removed as a shipped remote-reporting feature in Automation Map v2.1.8, kept here
as a reusable pattern for future projects).

**They are not interchangeable, and the distinction matters for user trust:**

| | Local diagnostic logging | Remote aggregate telemetry |
| --- | --- | --- |
| Transmits anything off the hub? | **No.** Writes only to the hub's own Logs page. | **Yes.** Sends a payload to a remote endpoint after every run. |
| Default state | Off | Runs automatically, no toggle |
| Who sees the data | Only the user, in their own hub's logs | The app's author, aggregated across every installation |
| User consent model | Explicit, per-session, self-expiring | Disclosed in documentation, not opt-in |

Automation Map originally shipped both. Community feedback on the remote telemetry specifically was
that an always-on reporting driver, however anonymous, read as intrusive - not because of what it
actually collected, but because of what having a "telemetry driver" installed implies. It was
removed entirely rather than made optional, because a driver that exists on the device list but
defaults to off still has to be trusted not to have been silently switched on, and that trust cost
was judged not worth what the data provided. Local diagnostic logging replaced it for the
troubleshooting use case; nothing replaced the aggregate-usage use case, on the view that an
author's curiosity about usage patterns does not justify an always-present reporting device on a
user's hub.

## When to use which

- **Local diagnostic logging**: any time you want to see what your own app is doing on your own
  hub without leaving development-grade log verbosity on permanently. This is almost always what
  you want, and should usually be the first thing added to a new app, not an afterthought.
- **Remote aggregate telemetry**: only when you have a specific, disclosed reason to collect
  anonymous usage data across installations you don't control (e.g. "how many people hit this
  code path" to justify further investment), and you are prepared for that decision itself to be
  visible and questioned. Prefer explicit opt-in over a disclosed-but-default-on driver if you use
  this pattern at all - Automation Map's own experience is the argument for that, not a
  hypothetical.

## Local diagnostic logging pattern

No files needed - this is a small pattern to copy into an app directly, not a separate tool.

**Shape:**

1. A settings-page `bool` input, off by default, `submitOnChange: true`. Hubitat does not render
   `description:` reliably on `bool`/`time` inputs (confirmed live across multiple apps) - use a
   `paragraph` next to the toggle to explain what it does, not `description:`.
2. A single gate function reading that setting, checked at the *start* of every diagnostic log
   call/function - not evaluated first and passed through a logging wrapper, since that still
   builds the message string even when logging is off. The point of the gate is that a disabled
   toggle costs one boolean test, nothing more.
3. A **durable deadline, not just a scheduled job**: a one-shot `runIn()` job does not fire late or
   catch up if the hub is down when it comes due (unlike a recurring `schedule()`, which Hubitat
   does re-establish) - a scheduled-only auto-disable can leave the toggle stuck on if the hub
   happens to reboot mid-session. Store a real expiry timestamp (`now() + 3600000`) once, the
   moment the toggle transitions off to on - not on every later settings save, which would
   otherwise silently extend the window each time the user saves anything else while it's on. Make
   the gate function itself check that timestamp (`now() < expiry`), not only the setting, so the
   toggle is correctly treated as expired even if the scheduled disable job was missed - and
   reconcile the displayed setting back to `false` the next time the settings page renders, so nothing
   shows as on past its real deadline.
4. A severity split, decided per-line, not applied uniformly: routine/lifecycle chatter (install/
   update confirmations, scheduling confirmations, endpoint-entry logs, successful-save
   confirmations, expected discard-of-superseded-work messages, verbose trace/debug detail) is
   gated behind the toggle. Failures and degraded outcomes - anything that can leave the app's
   output incomplete, stale, or wrong (a failed fetch, an invariant violation, a watchdog timeout,
   a failed recovery) - stay unconditional regardless of the toggle. A quiet install must never
   lose visibility into something actually going wrong just because troubleshooting wasn't
   pre-enabled.

**Example** (Groovy, Hubitat app context - `settings`/`app`/`runIn`/`unschedule` are platform
globals):

```groovy
// The durable expiry (state.diagnosticLoggingExpiresAt) is the real
// authority - the setting alone is not enough (see point 3 above).
boolean diagOn() {
    if (settings.diagnosticLoggingEnabled != true) return false
    Long expiresAt = (state.diagnosticLoggingExpiresAt ?: 0) as Long
    return expiresAt > 0 && now() < expiresAt
}

// Sets the deadline ONCE, on the off-to-on transition only - an unrelated
// later settings save while this stays on must not push it out further.
void scheduleDiagnosticLoggingExpiry() {
    if (settings.diagnosticLoggingEnabled != true) {
        unschedule('disableDiagnosticLogging')
        state.remove('diagnosticLoggingExpiresAt')
        return
    }
    if (!state.diagnosticLoggingExpiresAt) {
        state.diagnosticLoggingExpiresAt = now() + 3600000L
        unschedule('disableDiagnosticLogging')
        runIn(3600, 'disableDiagnosticLogging')
    }
}

void disableDiagnosticLogging() {
    app.updateSetting('diagnosticLoggingEnabled', [type: 'bool', value: false])
    state.remove('diagnosticLoggingExpiresAt')
    log.info "${app.label}: diagnostic logging auto-disabled after one hour"
}

// Settings page - call scheduleDiagnosticLoggingExpiry() from updated() AND
// at the top of the settings page itself, in that order, BEFORE the stale-
// on reconciliation below. This is not redundant: the toggle uses
// submitOnChange, which saves the setting and re-renders the page WITHOUT
// calling updated() - Done/Save Preferences is a separate, later event that
// calls updated(). Without also calling it on page render, the very first
// render after a user turns the toggle on would see the setting already
// true but no deadline set yet, and the reconciliation check would
// immediately flip it back off before updated() ever runs - the toggle
// would appear to do nothing. Calling it first is safe precisely because
// it is idempotent (see its own `if (!state.diagnosticLoggingExpiresAt)`
// guard above) - it only ever sets the deadline once, on a genuine
// off-to-on transition; every later call, from either place, is a no-op
// while it's already on.
//   scheduleDiagnosticLoggingExpiry()
//   if (settings.diagnosticLoggingEnabled == true && !diagOn()) {
//       disableDiagnosticLogging()
//   }
//   paragraph "Writes extra detail to your hub's Logs page for troubleshooting -
//              nothing here is transmitted anywhere. Off by default, and turns
//              itself back off automatically after one hour."
//   input name: 'diagnosticLoggingEnabled', type: 'bool',
//       title: 'Enable diagnostic logging', defaultValue: false, submitOnChange: true

// A gated call, anywhere in the app:
if (diagOn()) log.info "${app.label}: scan started"

// An unconditional call - a failure, never gated:
log.warn "${app.label}: could not list devices: ${result.error}"
```

Call `scheduleDiagnosticLoggingExpiry()` from **both** `updated()` and the dynamic-page render,
before the stale-on reconciliation check - see the comment above the settings-page snippet for why
the page-render call is not redundant with the `updated()` one.

## Remote aggregate telemetry pattern

A generalized, sanitized version of Automation Map's own implementation - a Hubitat app-owned
child device driver that reports a small fixed payload to a Google Apps Script web app, which
appends one row per report to a Google Sheet. Templates in this directory:

- [`telemetry-driver-template.groovy`](telemetry-driver-template.groovy) - the child device driver
  an app creates and calls.
- [`apps-script-webhook-template.gs`](apps-script-webhook-template.gs) - the receiving endpoint.
- [`deploy-apps-script.ps1`](deploy-apps-script.ps1) - a `clasp`-based deploy script that updates a
  live Apps Script deployment from this repository's source without needing to paste code into the
  browser editor each time.

### Threat and privacy boundaries

- **The endpoint has no secret and needs none.** It's open ingestion, protected by a strict
  server-side payload-shape check (fixed fields, fixed types, length-capped strings, an
  error-code allowlist rather than free text), not by a token. A secret embedded in a public,
  open-source driver authenticates no one - every installer can read it. The worst case of a
  malicious actor abusing the open endpoint is junk rows in the sheet, not a security breach.
- **Payload contents must be a fixed, reviewed field list, never free text.** Version strings,
  small bounded integers, and a small fixed set of category codes only. No names, no IPs, no
  hub identifiers beyond an optional hardware model string, no free-form user input of any kind
  ever enters the payload. The receiving script re-validates this shape independently of the
  driver - never trust the sender alone.
- **A telemetry failure must never affect the app's own function.** Every call into this pattern
  (creating the child device, sending a report, deferred network fetches for optional fields) is
  wrapped in try/catch, logged and swallowed, never thrown. Delivery is deferred (`runIn`) off the
  app's own success path, not called inline, so a slow or failing endpoint can never delay or risk
  whatever the app was actually doing.
- **Disclose it plainly wherever a user would look**, and reconsider whether disclosure is enough
  before defaulting it on - see the note at the top of this document. An opt-in default is safer
  for user trust than a disclosed-but-automatic one, even though the driver template here (matching
  what Automation Map originally shipped) defaults to automatic; treat that as the part most worth
  reconsidering for a new use of this pattern, not something to copy uncritically.

### Configuration placeholders

Nothing in these templates contains a real script ID, deployment ID, or spreadsheet ID - every
identifier is a placeholder with a comment explaining what to replace it with and where to find the
real value. Follow the deployment checklist at the top of `apps-script-webhook-template.gs` in
order; step numbers there matter, particularly around confirming a live deployment actually serves
the version you just edited, which is not automatic in the Apps Script editor.

## Performance diagnosis case study

[HAI Runtime latency and state](hai-runtime-performance-case-study.md) records a measured Hubitat
app performance investigation, the failed hypotheses, and a reusable timing method.
