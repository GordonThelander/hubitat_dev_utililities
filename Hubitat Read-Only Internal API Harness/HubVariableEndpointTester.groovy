definition(
    name: "Hub Variable Endpoint Tester",
    namespace: "gordonthelander",
    author: "Gordon Thelander",
    description: "Tests candidate internal endpoints for an authoritative Hub Variable inventory, without retaining variable names/values",
    category: "Utility",
    iconUrl: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
    iconX2Url: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
    singleInstance: true
)

// Purpose: settle one open question from Supporting Docs/hub_variable_first_class_spec.md
// (section 6.1/14 Q1) before any Automation Map code is written - which read-only internal
// endpoint, if any, gives a normal installed Hubitat app (the same execution context
// Automation Map itself runs in) an authoritative Hub Variable inventory, as opposed to the
// reference-derived subset Automation Map currently gets from decoding Rule Machine.
//
// Deliberately does NOT log or store variable names or values - both are household data per
// the spec's own privacy note (section 2, section 10). Only HTTP status, response shape, and
// (for the winning endpoint only) field NAMES/count are reported, never variable content.

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Hub Variable Endpoint Tester", install: true, uninstall: true) {
        section("Endpoint tests") {
            paragraph(
                "Tries several plausible internal endpoint paths for a Hub Variable inventory, " +
                "the same way the existing Internal Endpoint Tester already does for devices/apps/" +
                "rooms. Reports status, response shape, and field names only - never variable " +
                "names or values, since those are household data."
            )
            input("runTestsButton", "button", title: "Run endpoint tests")
            if (state.testRunning) {
                paragraph("<b>Tests are running.</b> Refresh this page in a few seconds.")
            }
        }

        section("Results") {
            if (state.testResults) {
                paragraph(state.testResults.join("<br>"))
            } else {
                paragraph("No tests have been run yet.")
            }
        }

        section("Important") {
            paragraph(
                "Delete this app once the winning endpoint (if any) is identified - it exists " +
                "only to answer one design question, not as a permanent tool."
            )
        }
    }
}

void installed() {
    initialize()
    // Runs automatically on install, not just on button press - lets this be
    // triggered by a non-interactive install (e.g. via API) without needing
    // to simulate the settings page's own button-click POST cycle.
    startEndpointTests()
}
void updated() { initialize() }

void initialize() {
    if (state.testRunning == null) state.testRunning = false
    if (state.testResults == null) state.testResults = []
}

void appButtonHandler(String buttonName) {
    if (buttonName == "runTestsButton") startEndpointTests()
}

void startEndpointTests() {
    if (state.testRunning) {
        appendResult("A test run is already in progress - wait for it to finish.")
        return
    }

    List endpoints = buildEndpointList()
    state.testResults = ["Starting ${endpoints.size()} candidate endpoint tests..."]
    state.testQueue = endpoints
    state.testRunning = true
    runNextEndpointTest()
}

// Candidates chosen from the /hub2/* and /hub/* naming conventions already confirmed working
// elsewhere on this exact hub this session (devicesList, appsList, roomsList, userAppTypes,
// details/json) - not a blind brute-force list.
List buildEndpointList() {
    return [
        [name: "Hub2 variables list",        path: "/hub2/variablesList",        expected: "ListOrMap"],
        [name: "Hub2 hub-variables list",     path: "/hub2/hubVariablesList",     expected: "ListOrMap"],
        [name: "Hub variables list (data)",   path: "/hub/variables/list/data",   expected: "ListOrMap"],
        [name: "Hub variables list",          path: "/hub/variables/list",        expected: "ListOrMap"],
        [name: "Hub variables json",          path: "/hub/variables/json",        expected: "ListOrMap"],
        [name: "Hub variables (page itself)", path: "/hub/variables",             expected: "Any"],
        [name: "Hub2 variable list (alt)",    path: "/hub2/variableList",         expected: "ListOrMap"],
    ]
}

void runNextEndpointTest() {
    List queue = state.testQueue ?: []

    if (queue.isEmpty()) {
        state.testRunning = false
        state.testQueue = []
        List completedResults = removeStartingMessage(state.testResults ?: [])
        completedResults << "<b>Testing complete.</b>"
        state.testResults = completedResults
        log.info "Hub Variable endpoint testing complete"
        return
    }

    Map endpoint = queue.remove(0) as Map
    state.testQueue = queue

    Map callbackData = [name: endpoint.name, path: endpoint.path, expected: endpoint.expected]
    Map requestParameters = [uri: "http://127.0.0.1:8080${endpoint.path}", timeout: 15]

    try {
        asynchttpGet("endpointTestCallback", requestParameters, callbackData)
    } catch (Exception exception) {
        appendResult("FAIL: ${endpoint.name} — request could not be started: ${safeMessage(exception.message)}")
        runNextEndpointTest()
    }
}

void endpointTestCallback(response, Map callbackData) {
    String name = callbackData.name ?: "Unknown endpoint"
    String path = callbackData.path ?: ""
    String expected = callbackData.expected ?: "Any"

    try {
        if (response == null) {
            appendResult("FAIL: ${name} (${path}) — no HTTP response")
        } else if (response.hasError()) {
            appendResult("FAIL: ${name} (${path}) — ${safeMessage(response.getErrorMessage())}")
        } else {
            Integer status = response.status as Integer
            Object responseData = readResponseData(response)
            String responseType = identifyResponseType(responseData)

            if (status != 200) {
                appendResult("FAIL: ${name} (${path}) — HTTP ${status}")
            } else if (responseData == null) {
                appendResult("CHECK: ${name} (${path}) — HTTP 200 but response body was null")
            } else if (isProbablyLoginPage(responseData)) {
                appendResult("CHECK: ${name} (${path}) — HTTP 200 but response appears to be an HTML login/nav page, not data")
            } else if (!matchesExpectedType(responseData, expected)) {
                appendResult("CHECK: ${name} (${path}) — HTTP 200, returned ${responseType}; expected ${expected}")
            } else {
                // Field names/shape only - never the actual variable names or values inside.
                String shapeSummary = summarizeShape(responseData)
                appendResult("PASS: ${name} (${path}) — HTTP 200, ${responseType}. Shape: ${shapeSummary}")
            }
        }
    } catch (Exception exception) {
        appendResult("FAIL: ${name} (${path}) — callback error: ${safeMessage(exception.message)}")
    }

    runNextEndpointTest()
}

// Reports STRUCTURE only - key names of the first record, and a count - deliberately never the
// values themselves, since a variable's name and current value are both household data per the
// spec this test exists to inform.
// instanceof-only - Hubitat's sandbox rejects reflective .getClass() calls
// even though local groovyc accepts them (confirmed live: "Expression
// [MethodCallExpression] is not allowed: first?.getClass()" on hub_create_app).
String typeName(Object value) {
    if (value == null) return "null"
    if (value instanceof Map) return "Map"
    if (value instanceof List) return "List"
    if (value instanceof String) return "String"
    if (value instanceof Boolean) return "Boolean"
    if (value instanceof Number) return "Number"
    return "Other"
}

String summarizeShape(Object value) {
    try {
        if (value instanceof Map) {
            List topKeys = (value as Map).keySet().toList()
            return "Map with top-level keys ${topKeys}"
        }
        if (value instanceof List) {
            List list = value as List
            if (list.isEmpty()) return "empty List"
            Object first = list[0]
            if (first instanceof Map) {
                List itemKeys = (first as Map).keySet().toList()
                return "List of ${list.size()} item(s), each a Map with keys ${itemKeys}"
            }
            return "List of ${list.size()} item(s) of type ${typeName(first)}"
        }
        return "scalar value of type ${typeName(value)}"
    } catch (Exception ignored) {
        return "(could not summarize shape)"
    }
}

Object readResponseData(response) {
    try {
        Object json = response.json
        if (json != null) return json
    } catch (Exception ignored) {
        // Not JSON.
    }
    return response.data
}

String identifyResponseType(Object value) {
    if (value == null) return "null"
    if (value instanceof Map) return "Map"
    if (value instanceof List) return "List"
    if (value instanceof String) return "String"
    if (value instanceof Number) return "Number"
    if (value instanceof Boolean) return "Boolean"
    return "Other"
}

Boolean matchesExpectedType(Object value, String expected) {
    if (expected == "Any") return true
    if (expected == "Map") return value instanceof Map
    if (expected == "List") return value instanceof List
    if (expected == "ListOrMap") return value instanceof List || value instanceof Map
    return true
}

Boolean isProbablyLoginPage(Object value) {
    if (!(value instanceof String)) return false
    String text = value.toString().toLowerCase()
    return text.contains("<html") || text.contains("<!doctype html") || text.contains("<form") || text.contains("/login")
}

void appendResult(String result) {
    List results = removeStartingMessage(state.testResults ?: [])
    results << result
    state.testResults = results
    log.info result.replaceAll(/<[^>]+>/, "")
}

List removeStartingMessage(List results) {
    if (results && results[0] instanceof String && results[0].startsWith("Starting ")) {
        results.remove(0)
    }
    return results
}

String safeMessage(Object message) {
    if (message == null) return "unknown error"
    String text = message.toString()
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if (text.size() > 300) text = text.substring(0, 300) + "..."
    return text
}
