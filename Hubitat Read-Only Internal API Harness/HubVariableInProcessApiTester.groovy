definition(
    name: "Hub Variable In-Process API Tester",
    namespace: "gordonthelander",
    author: "Gordon Thelander",
    description: "Tests getAllGlobalVars()/getGlobalVar() - Hubitat's in-process SmartApp API - for authoritative Hub Variable inventory, without retaining variable names/values",
    category: "Utility",
    iconUrl: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
    iconX2Url: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
    singleInstance: true
)

// Purpose: settle the open question from Supporting Docs/hub_variable_first_class_spec.md section
// 6.1/Q6 - the prior HubVariableEndpointTester found no loopback HTTP endpoint for an authoritative
// Hub Variable inventory (all 7 candidates 404). Codex (via public Hubitat staff docs) and a direct
// source read of a real installed app both independently point to the answer being an IN-PROCESS
// SmartApp API method instead: getAllGlobalVars() / getGlobalVar(String name). This app tests that
// directly, from an ORDINARY installed app (not the specialized MCP Rule Server that showed the
// pattern), and confirms it's a generally-available mechanism.
//
// Deliberately does NOT log or store variable NAMES or VALUES - both are household data per the
// spec's own privacy note (section 2, section 10). Only counts, declared types, key-name shapes,
// and whether a Connector deviceId is present are reported - the value field is never read.

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Hub Variable In-Process API Tester", install: true, uninstall: true) {
        section("Test") {
            paragraph(
                "Tests getAllGlobalVars() and getGlobalVar() - Hubitat's documented in-process " +
                "SmartApp Hub Variable API, not an HTTP endpoint - from this ordinary installed app. " +
                "Reports counts, declared types, and Connector-deviceId presence only - never " +
                "variable names or values, since those are household data."
            )
            input("runTestsButton", "button", title: "Run test")
        }

        section("Results") {
            if (state.testResults) {
                paragraph(state.testResults.join("<br>"))
            } else {
                paragraph("No test has been run yet.")
            }
        }

        section("Important") {
            paragraph(
                "Delete this app once the result is recorded - it exists only to answer one design " +
                "question, not as a permanent tool."
            )
        }
    }
}

void installed() {
    initialize()
    // Auto-runs on install, matching HubVariableEndpointTester's pattern, so results are ready
    // as soon as the instance is created without needing a separate button-click POST.
    runTest()
}
void updated() { initialize() }

void initialize() {
    if (state.testResults == null) state.testResults = []
}

void appButtonHandler(String buttonName) {
    if (buttonName == "runTestsButton") runTest()
}

void runTest() {
    List results = []

    // Test 1: getAllGlobalVars() - the enumeration method. Never touch the 'value' key on any
    // entry; only inspect entry shape (declared type, key set, Connector deviceId presence).
    Map allVars = null
    try {
        allVars = getAllGlobalVars()
        if (allVars == null) {
            results << "getAllGlobalVars(): returned null"
        } else {
            results << "getAllGlobalVars(): SUCCESS - result type ${typeName(allVars)}, count=${allVars.size()}"
            allVars.each { name, meta ->
                if (meta instanceof Map) {
                    String declaredType = meta.type?.toString() ?: "(no 'type' key)"
                    boolean hasConnectorDeviceId = meta.containsKey("deviceId") && meta.deviceId != null
                    Set entryKeys = meta.keySet()
                    results << "  entry: declaredType=${declaredType}, hasConnectorDeviceId=${hasConnectorDeviceId}, keys=${entryKeys}"
                } else {
                    results << "  entry: unexpected shape, entryType=${typeName(meta)} (not a Map)"
                }
            }
        }
    } catch (Exception exception) {
        results << "getAllGlobalVars(): THREW ${exceptionLabel(exception)}: ${safeMessage(exception.message)}"
    }

    // Test 2: getGlobalVar(String name) - the single-variable read method, called bare (in app
    // scope), not via location.getGlobalVar() - per the community-confirmed scope requirement.
    // Reuses one name discovered above only to prove the call path works; still never reads value.
    try {
        String firstName = (allVars instanceof Map && !allVars.isEmpty()) ? (allVars.keySet().toList()[0] as String) : null
        if (firstName) {
            def single = getGlobalVar(firstName)
            boolean hasValueField = (single instanceof Map) ? single.containsKey("value") : false
            results << "getGlobalVar(<one existing name>): SUCCESS - result type ${typeName(single)}, hasValueField=${hasValueField}"
        } else {
            results << "getGlobalVar(): skipped - no variables available from getAllGlobalVars() to test with"
        }
    } catch (Exception exception) {
        results << "getGlobalVar(): THREW ${exceptionLabel(exception)}: ${safeMessage(exception.message)}"
    }

    state.testResults = results
    results.each { log.info it.replaceAll(/<[^>]+>/, "") }
}

// instanceof-only - Hubitat's sandbox rejects reflective .getClass() calls even though local
// groovyc accepts them (confirmed live this session: "Expression [MethodCallExpression] is not
// allowed: first?.getClass()" on hub_create_app).
String typeName(Object value) {
    if (value == null) return "null"
    if (value instanceof Map) return "Map"
    if (value instanceof List) return "List"
    if (value instanceof String) return "String"
    if (value instanceof Boolean) return "Boolean"
    if (value instanceof Number) return "Number"
    return "Other"
}

// Exception class name without .getClass() - same sandbox restriction as typeName() above.
String exceptionLabel(Exception exception) {
    String text = exception?.toString()
    if (!text) return "Exception"
    int colonIdx = text.indexOf(":")
    return colonIdx > 0 ? text.substring(0, colonIdx) : text
}

String safeMessage(Object message) {
    if (message == null) return "unknown error"
    String text = message.toString()
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if (text.size() > 300) text = text.substring(0, 300) + "..."
    return text
}
