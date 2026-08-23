definition(
    name: "Read-Only Internal Endpoint Tester",
    namespace: "gordonthelander",
    author: "Gordon Thelander",
    description: "Tests availability and response types for read-only Hubitat internal endpoints without retaining response bodies",
    category: "Utility",
    iconUrl: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
    iconX2Url: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(
        name: "mainPage",
        title: "Read-Only Internal Endpoint Tester",
        install: true,
        uninstall: true
    ) {
        section("Optional endpoint IDs") {
            paragraph(
                "Enter IDs to test endpoints that require a specific device, " +
                "installed app, or source-code record."
            )

            input(
                "deviceIdToTest",
                "number",
                title: "Device ID",
                required: false
            )

            input(
                "appIdToTest",
                "number",
                title: "Installed App ID",
                required: false
            )

            input(
                "appCodeIdToTest",
                "number",
                title: "App Code ID",
                required: false
            )

            input(
                "driverCodeIdToTest",
                "number",
                title: "Driver Code ID",
                required: false
            )

            input(
                "libraryCodeIdToTest",
                "number",
                title: "Library Code ID",
                required: false
            )

            input(
                "deviceToTest",
                "capability.*",
                title: "Device to test (optional, for in-process property tests)",
                required: false
            )
        }

        section("Endpoint tests") {
            input(
                "runTestsButton",
                "button",
                title: "Run endpoint tests"
            )

            if (state.testRunning) {
                paragraph(
                    "<b>Tests are running.</b> Refresh this page in a few seconds."
                )
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
                "This app records only the HTTP status and general response type. " +
                "It does not save endpoint response bodies. Some internal endpoints, " +
                "especially installed-app status, can expose passwords or access tokens."
            )
        }
    }
}

void installed() {
    initialize()
}

void updated() {
    initialize()
}

void initialize() {
    if (state.testRunning == null) {
        state.testRunning = false
    }

    if (state.testResults == null) {
        state.testResults = []
    }
}

void appButtonHandler(String buttonName) {
    if (buttonName == "runTestsButton") {
        startEndpointTests()
    }
}

void startEndpointTests() {
    // Guards against a double-click starting a second queue while the first
    // is still landing callbacks - without this, a callback from the old run
    // would intermix with the new run's queue instead of belonging to either
    // one cleanly. This app only ever runs one queue at a time, so a boolean
    // guard is sufficient here.
    if (state.testRunning) {
        appendResult("A test run is already in progress - wait for it to finish.")
        return
    }

    List endpoints = buildEndpointList()

    state.testResults = [
        "Starting ${endpoints.size()} endpoint tests..."
    ]

    state.testQueue = endpoints
    state.testRunning = true

    testInProcessProperties()
    runNextEndpointTest()
}

/**
 * Device/app/hub properties that need no HTTP call, run synchronously (no
 * queue, no callback) before the async HTTP endpoint tests start. Unlike
 * the HTTP endpoints, an unsupported property/method on these typed
 * wrapper objects throws rather than returning null, so each read is
 * wrapped defensively and reported as CHECK rather than crashing the run.
 */
void testInProcessProperties() {
    Long uptime = readHubUptime()
    appendResult(
        uptime != null
            ? "PASS: Hub uptime (in-process) - ${uptime}"
            : "CHECK: Hub uptime (in-process) - property not available on this firmware"
    )

    String installState = readAppInstallationState(app)
    appendResult(
        installState != null
            ? "PASS: App installation state (in-process) - ${installState}"
            : "CHECK: App installation state (in-process) - property not available"
    )

    if (deviceToTest == null) {
        appendResult("CHECK: Device-scoped in-process properties skipped - no device selected")
        return
    }

    Map<String, Closure> deviceProps = [
        "Device controllerType"    : { readDeviceControllerType(deviceToTest) },
        "Device disabled"          : { readDeviceDisabled(deviceToTest) },
        "Device getData()"         : { readDeviceData(deviceToTest) },
        "Device getTypeName()"     : { readDeviceTypeName(deviceToTest) },
        "Device driverType"        : { readDeviceDriverType(deviceToTest) },
        "Device lastActivity"      : { readDeviceLastActivity(deviceToTest) },
        "Device status"            : { readDeviceStatus(deviceToTest) }
    ]

    deviceProps.each { String label, Closure reader ->
        Object value = reader.call()
        appendResult(
            value != null
                ? "PASS: ${label} (in-process) - ${value}"
                : "CHECK: ${label} (in-process) - property not available"
        )
    }
}

Long readHubUptime() {
    try { return location?.hub?.uptime as Long } catch (Exception ignored) { return null }
}

String readDeviceControllerType(dev) {
    try { return dev?.controllerType as String } catch (Exception ignored) { return null }
}

Boolean readDeviceDisabled(dev) {
    try { return dev?.disabled as Boolean } catch (Exception ignored) { return null }
}

Object readDeviceData(dev) {
    try { return dev?.getData() } catch (Exception ignored) { return null }
}

String readDeviceTypeName(dev) {
    try { return dev?.getTypeName() as String } catch (Exception ignored) { return null }
}

String readDeviceDriverType(dev) {
    try { return dev?.driverType as String } catch (Exception ignored) { return null }
}

Object readDeviceLastActivity(dev) {
    try { return dev?.getLastActivity() } catch (Exception ignored) { return null }
}

String readDeviceStatus(dev) {
    try { return dev?.status as String } catch (Exception ignored) { return null }
}

String readAppInstallationState(targetApp) {
    try { return targetApp?.getInstallationState() as String } catch (Exception ignored) { return null }
}

List buildEndpointList() {
    List endpoints = [
        [
            name: "Hub details",
            path: "/hub/details/json",
            expected: "Map"
        ],
        [
            name: "CPU information",
            path: "/hub/cpuInfo",
            expected: "Any"
        ],
        [
            name: "Free OS memory",
            path: "/hub/advanced/freeOSMemory",
            expected: "Any"
        ],
        [
            name: "Free OS memory history (candidate)",
            path: "/hub/advanced/freeOSMemoryHistory",
            expected: "Any",
            candidate: true
        ],
        [
            name: "Latest free OS memory (candidate)",
            path: "/hub/advanced/freeOSMemoryLast",
            expected: "Any",
            candidate: true
        ],
        [
            name: "Database size",
            path: "/hub/advanced/databaseSize",
            expected: "Any"
        ],
        [
            name: "Internal temperature",
            path: "/hub/advanced/internalTempCelsius",
            expected: "Any"
        ],
        [
            name: "Z-Wave details (candidate)",
            path: "/hub/zwaveDetails/json",
            expected: "ListOrMap",
            candidate: true
        ],
        [
            name: "Zigbee details (candidate)",
            path: "/hub/zigbeeDetails/json",
            expected: "ListOrMap",
            candidate: true
        ],
        [
            name: "Matter details (candidate)",
            path: "/hub/matterDetails/json",
            expected: "ListOrMap",
            candidate: true
        ],
        [
            name: "Zigbee child and route information (candidate)",
            path: "/hub/zigbee/getChildAndRouteInfo",
            expected: "String",
            candidate: true
        ],
        [
            name: "Zigbee child and route JSON (candidate)",
            path: "/hub/zigbee/getChildAndRouteInfoJson",
            expected: "ListOrMap",
            candidate: true
        ],
        [
            name: "Device list",
            path: "/device/list/data",
            expected: "ListOrMap"
        ],
        [
            name: "Device drivers",
            path: "/device/drivers",
            expected: "ListOrMap"
        ],
        [
            name: "Installed applications",
            path: "/installedapp/list/data",
            expected: "ListOrMap"
        ],
        [
            name: "Hub2 device list",
            path: "/hub2/devicesList",
            expected: "ListOrMap"
        ],
        [
            name: "Hub2 application list",
            path: "/hub2/appsList",
            expected: "ListOrMap"
        ],
        [
            name: "User App Code types",
            path: "/hub2/userAppTypes",
            expected: "ListOrMap"
        ],
        [
            name: "User driver types (candidate)",
            path: "/hub2/userDeviceTypes",
            expected: "ListOrMap",
            candidate: true
        ],
        [
            name: "Rooms",
            path: "/hub2/roomsList",
            expected: "ListOrMap"
        ],
        [
            name: "Hub data (candidate)",
            path: "/hub2/hubData",
            expected: "Map",
            candidate: true
        ],
        [
            name: "Hub Mesh data (candidate)",
            path: "/hub2/hubMeshJson",
            expected: "ListOrMap",
            candidate: true
        ],
        [
            name: "Network configuration (candidate, sensitive)",
            path: "/hub2/networkConfiguration",
            expected: "Map",
            candidate: true
        ]
    ]

    if (deviceIdToTest != null) {
        endpoints << [
            name: "Device full JSON",
            path: "/device/fullJson/${numericId(deviceIdToTest)}",
            expected: "Map"
        ]
    }

    if (appIdToTest != null) {
        endpoints << [
            name: "Installed app status",
            path: "/installedapp/statusJson/${numericId(appIdToTest)}",
            expected: "Map"
        ]
    }

    if (appCodeIdToTest != null) {
        endpoints << [
            name: "App Code source",
            path: "/app/ajax/code?id=${numericId(appCodeIdToTest)}",
            expected: "Map"
        ]
    }

    if (driverCodeIdToTest != null) {
        endpoints << [
            name: "driver source",
            path: "/driver/ajax/code?id=${numericId(driverCodeIdToTest)}",
            expected: "Map"
        ]
    }

    if (libraryCodeIdToTest != null) {
        endpoints << [
            name: "library source",
            path: "/library/ajax/code?id=${numericId(libraryCodeIdToTest)}",
            expected: "Map"
        ]
    }

    return endpoints
}

String numericId(value) {
    try {
        return (value as Long).toString()
    } catch (Exception ignored) {
        return "0"
    }
}

void runNextEndpointTest() {
    List queue = state.testQueue ?: []

    if (queue.isEmpty()) {
        state.testRunning = false
        state.testQueue = []
        state.testResults = removeStartingMessage(state.testResults ?: [])

        List completedResults = state.testResults ?: []
        completedResults << "<b>Testing complete.</b>"
        state.testResults = completedResults

        log.info "Internal endpoint testing complete"
        return
    }

    Map endpoint = queue.remove(0) as Map
    state.testQueue = queue

    Map callbackData = [
        name: endpoint.name,
        path: endpoint.path,
        expected: endpoint.expected,
        candidate: endpoint.candidate == true
    ]

    Map requestParameters = [
        uri: "http://127.0.0.1:8080${endpoint.path}",
        timeout: 15
    ]

    try {
        asynchttpGet(
            "endpointTestCallback",
            requestParameters,
            callbackData
        )
    } catch (Exception exception) {
        appendResult(
            "FAIL: ${endpoint.name} - request could not be started: " +
            safeMessage(exception.message)
        )

        runNextEndpointTest()
    }
}

void endpointTestCallback(response, Map callbackData) {
    String name = callbackData.name ?: "Unknown endpoint"
    String path = callbackData.path ?: ""
    String expected = callbackData.expected ?: "Any"
    Boolean candidate = callbackData.candidate == true
    String unavailableLevel = candidate ? "CHECK" : "FAIL"

    try {
        if (response == null) {
            appendResult(
                "${unavailableLevel}: ${name} - no HTTP response"
            )
        } else if (response.hasError()) {
            appendResult(
                "${unavailableLevel}: ${name} - ${safeMessage(response.getErrorMessage())}"
            )
        } else {
            Integer status = response.status as Integer
            Object responseData = readResponseData(response)
            String responseType = identifyResponseType(responseData)

            if (status != 200) {
                appendResult(
                    "${unavailableLevel}: ${name} - HTTP ${status}"
                )
            } else if (responseData == null) {
                appendResult(
                    "CHECK: ${name} - HTTP 200 but response body was null"
                )
            } else if (!matchesExpectedType(responseData, expected)) {
                appendResult(
                    "CHECK: ${name} - HTTP 200, returned ${responseType}; " +
                    "expected ${expected}. It may be a login page or its schema may have changed."
                )
            } else if (isProbablyLoginPage(responseData)) {
                appendResult(
                    "CHECK: ${name} - HTTP 200 but response appears to be an HTML login page"
                )
            } else {
                appendResult(
                    "PASS: ${name} - HTTP 200, ${responseType}"
                )
            }
        }
    } catch (Exception exception) {
        appendResult(
            "FAIL: ${name} - callback error: " +
            safeMessage(exception.message)
        )
    }

    runNextEndpointTest()
}

/**
 * hubitat.scheduling.AsyncResponse.getData() always returns the raw response
 * body as a String - unlike the synchronous httpGet closure's resp.data,
 * which Hubitat auto-parses by content type. Only getJson() parses it. Most
 * of these endpoints return JSON, but a few (cpuInfo, freeOSMemory,
 * databaseSize, internalTempCelsius) return plain text that .json can't
 * parse, so this falls back to the raw string rather than failing the test.
 */
Object readResponseData(response) {
    try {
        Object json = response.json
        if (json != null) {
            return json
        }
    } catch (Exception ignored) {
        // Not JSON - expected for the plain-text hub-health endpoints.
    }
    return response.data
}

String identifyResponseType(Object value) {
    if (value == null) {
        return "null"
    }

    if (value instanceof Map) {
        return "Map"
    }

    if (value instanceof List) {
        return "List"
    }

    if (value instanceof String) {
        return "String"
    }

    if (value instanceof Number) {
        return "Number"
    }

    if (value instanceof Boolean) {
        return "Boolean"
    }

    return "Other"
}

Boolean matchesExpectedType(Object value, String expected) {
    if (expected == "Any") {
        return true
    }

    if (expected == "Map") {
        return value instanceof Map
    }

    if (expected == "List") {
        return value instanceof List
    }

    if (expected == "ListOrMap") {
        return value instanceof List || value instanceof Map
    }

    if (expected == "String") {
        return value instanceof String
    }

    return true
}

Boolean isProbablyLoginPage(Object value) {
    if (!(value instanceof String)) {
        return false
    }

    String text = value.toString().toLowerCase()

    return text.contains("<html") ||
        text.contains("<!doctype html") ||
        text.contains("<form") ||
        text.contains("/login")
}

void appendResult(String result) {
    List results = state.testResults ?: []
    results = removeStartingMessage(results)
    results << result
    state.testResults = results

    log.info result.replaceAll(/<[^>]+>/, "")
}

List removeStartingMessage(List results) {
    if (
        results &&
        results[0] instanceof String &&
        results[0].startsWith("Starting ")
    ) {
        results.remove(0)
    }

    return results
}

String safeMessage(Object message) {
    if (message == null) {
        return "unknown error"
    }

    String text = message.toString()

    text = text.replace("&", "&amp;")
    text = text.replace("<", "&lt;")
    text = text.replace(">", "&gt;")

    if (text.size() > 300) {
        text = text.substring(0, 300) + "..."
    }

    return text
}
