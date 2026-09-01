/*
 * Telemetry Driver Template
 *
 * Copyright 2026 Gordon Thelander
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * Generalized from Automation Map's own telemetry driver (removed as a shipped
 * feature in Automation Map v2.1.8; kept here as a reusable pattern). See this
 * directory's README for the threat/privacy boundaries this design assumes -
 * read that before reusing this, not just this file.
 *
 * Reports a small, fixed, reviewed payload shape to a fixed collection
 * endpoint - never device/app names, hub identity, IP or location. Disclosed
 * wherever the parent app documents itself. No credential: the endpoint is
 * open ingestion, protected server-side by strict payload validation, not by a
 * secret - a shared secret embedded in open-source driver source authenticates
 * no one once every installer can read it.
 *
 * Created and called only by the parent app itself, as its own child device -
 * not intended to be installed or driven standalone. Customize the payload
 * fields in report()/validateReport() to match what your app actually needs
 * to send; the fields here are Automation Map's own example, not a required
 * shape.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = '1.0.0'
// Replace with your own deployed Apps Script web app /exec URL (see
// apps-script-webhook-template.gs's own deployment checklist). Never commit a
// real production URL to a public template - this placeholder is deliberate.
@Field static final String TELEMETRY_URL = 'https://script.google.com/macros/s/REPLACE_WITH_YOUR_DEPLOYMENT_ID/exec'

metadata {
    definition(
        name: "Telemetry Driver Template",
        namespace: "YourNamespace",
        author: "Your Name",
        importUrl: "https://raw.githubusercontent.com/your-org/your-repo/main/drivers/telemetry_driver.groovy"
    ) {
        capability "Actuator"
        attribute "lastStatus", "string"
        attribute "lastSentAt", "string"
        command "report", [[name: "data", type: "JSON_OBJECT", description: "See report()/validateReport() for the required shape"]]
    }
}

void report(Map data) {
    Map validation = validateReport(data)
    if (!validation.ok) {
        sendEvent(name: "lastStatus", value: "rejected: ${validation.error}")
        sendEvent(name: "lastSentAt", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        return
    }
    // Example payload shape - replace with your own fixed, reviewed field
    // list. Every field must be a small bounded value (version string,
    // small integer, fixed category code) - never free text, never
    // anything a user typed.
    Map body = [
        appVersion : data?.appVersion,
        timestamp  : data?.timestamp
    ]
    Map params = [
        uri              : TELEMETRY_URL,
        contentType      : "application/json",
        requestContentType: "application/json",
        body             : body,
        // Apps Script normally redirects its web-app response to a
        // googleusercontent URL. Ask Hubitat to follow that redirect so the
        // final JSON acknowledgement can be inspected when the platform
        // supports it. The callback also handles an exposed redirect safely.
        followRedirects  : true,
        timeout          : 10
    ]
    try {
        asynchttpPost("telemetryResponse", params)
    } catch (Exception ex) {
        sendEvent(name: "lastStatus", value: "error: ${ex.message}")
        sendEvent(name: "lastSentAt", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    }
}

// Strict shape check, matching the server-side check in the Apps Script
// webhook - never trust the sender alone on either side.
private Map validateReport(Map data) {
    if (!data) return [ok: false, error: "missing report"]
    if (!(data.appVersion instanceof CharSequence) || !data.appVersion.toString().trim()) {
        return [ok: false, error: "missing appVersion"]
    }
    if (!(data.timestamp instanceof CharSequence) ||
        !(data.timestamp.toString() ==~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/)) {
        return [ok: false, error: "invalid timestamp"]
    }
    return [ok: true]
}

void telemetryResponse(resp, Map data = null) {
    try {
        int status = (resp?.status ?: 0) as int
        boolean redirected = status in [301, 302, 303, 307, 308]
        boolean transportOk = resp != null && !resp.hasError() && status == 200
        Map responseBody = transportOk ? (resp.getJson() as Map) : null
        boolean accepted = transportOk && responseBody?.ok == true
        // Hubitat may expose Apps Script's normal redirect instead of
        // following it. The POST has reached and executed the web app at that
        // point, but the redirected JSON body is unavailable, so report the
        // precise state as submitted rather than a false error.
        String detail = accepted ? "ok" : redirected ? "submitted" :
            (responseBody?.error ? "rejected: ${responseBody.error}" : "error: HTTP ${status ?: 'unknown'}")
        sendEvent(name: "lastStatus", value: detail.take(255))
    } catch (Exception ex) {
        // hasError()/status can themselves throw on some failure shapes - never
        // let a telemetry response failure surface anywhere the caller notices.
        sendEvent(name: "lastStatus", value: "error: ${ex.message}")
    }
    sendEvent(name: "lastSentAt", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}
