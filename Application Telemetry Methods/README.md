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
3. An auto-disable timer (`runIn(3600, 'disableDiagnosticLogging')` or similar), (re)scheduled
   whenever the toggle turns on and cancelled if the user turns it off early, so a session can
   never be left running by accident. Reschedule it from `updated()` too, not only the point where
   the user flips the toggle, so a hub reboot mid-session doesn't leave it stuck on indefinitely.
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
boolean diagOn() {
    return settings.diagnosticLoggingEnabled == true
}

void scheduleDiagnosticLoggingExpiry() {
    unschedule('disableDiagnosticLogging')
    if (settings.diagnosticLoggingEnabled == true) {
        runIn(3600, 'disableDiagnosticLogging')
    }
}

void disableDiagnosticLogging() {
    app.updateSetting('diagnosticLoggingEnabled', [type: 'bool', value: false])
    log.info "${app.label}: diagnostic logging auto-disabled after one hour"
}

// Settings page:
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

Call `scheduleDiagnosticLoggingExpiry()` from `updated()`.

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
