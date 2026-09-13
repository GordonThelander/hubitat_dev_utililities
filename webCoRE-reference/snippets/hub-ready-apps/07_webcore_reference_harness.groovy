// Installable, read-only Hubitat harness for the webCoRE developer-reference contracts.
// Generated from snippets 01 to 06; reports fixed codes and counts without retaining piston content.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

definition(
    name: 'webCoRE Reference Harness',
    namespace: 'gordon.thelander',
    author: 'Gordon Thelander',
    description: 'Runs the webCoRE developer-reference contracts against deterministic fixtures or one selected piston.',
    category: 'Convenience',
    singleInstance: true
)

preferences {
    page(name: 'mainPage')
}

void installed() { initialize() }
void updated() { initialize() }
void initialize() { }

def mainPage() {
    dynamicPage(name: 'mainPage', title: 'webCoRE Reference Harness', install: true, uninstall: true) {
        section('Contract self-check') {
            paragraph 'Runs all six reference contracts against deterministic synthetic fixtures.'
            input 'runSelfChecks', 'button', title: 'Run contract self-checks'
        }
        section('Live piston inspection') {
            paragraph 'Enter the installed-app ID of one webCoRE piston. The harness derives and verifies that piston\'s own parent before resolving device tokens. Both endpoints are read through the hub loopback and nothing is written to webCoRE.'
            input 'pistonAppId', 'text', title: 'Piston installed-app ID', required: false, submitOnChange: true
            input 'inspectPiston', 'button', title: 'Inspect selected piston'
        }
        section('Last result') {
            paragraph renderHarnessReport(state.lastReferenceReport as Map)
        }
        section('Data handling') {
            paragraph 'The harness retains only fixed result codes and aggregate counts. It does not log or store chunks, decoded documents, device tokens, device labels, variable values, URLs or credentials.'
        }
    }
}

void appButtonHandler(String buttonName) {
    if (buttonName == 'runSelfChecks') {
        state.lastReferenceReport = runReferenceSelfChecks()
    } else if (buttonName == 'inspectPiston') {
        state.lastReferenceReport = inspectReferencePiston(settings.pistonAppId)
    }
}

String fixedText(Object value) {
    String text = value == null ? '' : value.toString()
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
}

String renderHarnessReport(Map report) {
    if (!report) return 'No check has been run.'
    List<String> lines = []
    lines << "<b>${fixedText(report.mode ?: 'result')}: ${fixedText(report.status ?: 'unknown')}</b>"
    if (report.error) lines << "Error: ${fixedText(report.error)}"
    (report.checks instanceof Map ? report.checks as Map : [:]).keySet().sort().each { Object key ->
        lines << "${fixedText(key)}: ${fixedText(report.checks[key])}"
    }
    (report.metrics instanceof Map ? report.metrics as Map : [:]).keySet().sort().each { Object key ->
        lines << "${fixedText(key)}: ${fixedText(report.metrics[key])}"
    }
    return lines.join('<br>')
}

Map fetchReferenceStatusJson(String rawAppId) {
    String appId = rawAppId?.trim()
    if (!(appId ==~ /^[0-9]+$/)) return [ok: false, error: 'invalid-app-id']
    Map responseData = null
    try {
        httpGet([
            uri: "http://127.0.0.1:8080/installedapp/statusJson/${appId}",
            contentType: 'application/json',
            timeout: 20
        ]) { response ->
            if (response?.status == 200 && response.data instanceof Map) responseData = response.data as Map
        }
    } catch (Exception ignored) {
        return [ok: false, error: 'status-read-failed']
    }
    return responseData == null ? [ok: false, error: 'status-response-invalid'] : [ok: true, data: responseData]
}


// Reassembles and decodes a piston's saved chunk:N settings into its JSON document.
// Implements webcore_hubitat_architecture_and_saved_piston_reference.md section 5.


Map decodeLimits() {
    return [maxChunkIndex: 255, maxEncodedLength: 2_000_000]
}

// statusJson is the map returned by /installedapp/statusJson/<pistonAppId>.
// Returns [status: 'complete', document: Map], [status: 'not-present'] or [status: 'error', error: code].
Map decodePistonChunks(Map statusJson, Map limits = decodeLimits()) {
    if (statusJson == null || !statusJson.containsKey('appSettings') || statusJson.appSettings == null) {
        return [status: 'error', error: 'missing-settings']
    }
    Object settings = statusJson.appSettings
    if (!(settings instanceof List)) return [status: 'error', error: 'unexpected-settings']

    Map<Integer, String> chunks = [:]
    boolean duplicate = false
    boolean outOfRange = false
    (settings ?: []).each { Object raw ->
        if (!(raw instanceof Map)) return
        def match = ("${(raw as Map).name ?: ''}" =~ /^chunk:([0-9]+)$/)
        if (!match.matches()) return
        String digits = match[0][1] as String
        if (digits.length() > 9 || (digits as long) > (limits.maxChunkIndex as long)) { outOfRange = true; return }
        int index = digits as int
        if (chunks.containsKey(index)) duplicate = true
        Object value = (raw as Map).value
        chunks[index] = value == null ? null : value.toString()
    }

    // A piston that was never saved has no chunks: absence, not corruption.
    if (!chunks && !outOfRange) return [status: 'not-present']
    if (outOfRange) return [status: 'error', error: 'chunk-index-out-of-range']
    if (duplicate) return [status: 'error', error: 'duplicate-chunk']
    if (!chunks.containsKey(0)) return [status: 'error', error: 'missing-chunk-zero']
    int maximum = chunks.keySet().max() as int
    if ((0..maximum).any { !chunks.containsKey(it) }) return [status: 'error', error: 'missing-chunk']
    if ((0..maximum).any { !chunks[it] }) return [status: 'error', error: 'empty-chunk']
    long encodedLength = (0..maximum).sum { chunks[it].length() } as long
    if (encodedLength > (limits.maxEncodedLength as long)) return [status: 'error', error: 'configuration-too-large']

    byte[] bytes
    try {
        bytes = (0..maximum).collect { chunks[it] }.join('').decodeBase64()
    } catch (Exception ignored) {
        return [status: 'error', error: 'invalid-base64']
    }

    Object document
    try {
        document = new JsonSlurper().parseText(decodeWebcoreEmoji(new String(bytes, 'UTF-8')))
    } catch (Exception ignored) {
        return [status: 'error', error: 'invalid-json']
    }
    if (!(document instanceof Map)) return [status: 'error', error: 'unexpected-root']
    return [status: 'complete', document: document]
}

// webCoRE stores a 4-byte UTF-8 character (emoji) as ":%XX%XX%XX%XX:".
String decodeWebcoreEmoji(String value) {
    if (!value) return ''
    return value.replaceAll(/(:%[0-9A-F]{2}%[0-9A-F]{2}%[0-9A-F]{2}%[0-9A-F]{2}:)/) { Object match ->
        String token = (match instanceof List ? match[0] : match) as String
        URLDecoder.decode(token.substring(1, 13), 'UTF-8')
    }
}

// Hubitat adapter. The endpoint is internal and undocumented; it is served on the hub's loopback.
//
// Map fetchStatusJson(String appId) {
//     Map out = null
//     httpGet([uri: "http://127.0.0.1:8080/installedapp/statusJson/${appId}",
//              contentType: 'application/json', timeout: 20]) { resp -> out = resp.data as Map }
//     return out
// }
// Map decoded = decodePistonChunks(fetchStatusJson(pistonAppId))


// Computes webCoRE device tokens and resolves them against one parent's permitted devices.
// Implements device_and_variable_resolution.md section 2.


// ":" + lowercase hex MD5("core." + deviceId) + ":". Not salted per parent.
String webcoreDeviceToken(String deviceId) {
    byte[] digest = MessageDigest.getInstance('MD5').digest("core.${deviceId}".getBytes('UTF-8'))
    StringBuilder hex = new StringBuilder()
    digest.each { byte b -> hex << String.format('%02x', b & 0xFF) }
    return ":${hex}:"
}

// Device IDs a webCoRE parent makes available, from the parent's own statusJson.
List<String> permittedDeviceIds(Map parentStatusJson) {
    if (parentStatusJson == null || !parentStatusJson.containsKey('appSettings')
            || !(parentStatusJson.appSettings instanceof List)) return null
    Set<String> ids = [] as TreeSet<String>
    (parentStatusJson.appSettings as List).each { Object raw ->
        if (!(raw instanceof Map)) return
        Map setting = raw as Map
        if (!"${setting.type ?: ''}".startsWith('capability')) return
        (setting.deviceIdsForDeviceList instanceof List ? setting.deviceIdsForDeviceList as List : []).each { Object id ->
            if (id != null) ids << id.toString()
        }
    }
    return ids as List<String>
}

// token -> set of device IDs. Build one index per parent; never merge parents.
Map<String, Set<String>> buildTokenIndex(List<String> deviceIds, Closure tokenFn = { String id -> webcoreDeviceToken(id) }) {
    if (deviceIds == null) return null
    Map<String, Set<String>> index = [:]
    deviceIds.each { String id ->
        String token = tokenFn(id) as String
        if (!index[token]) index[token] = [] as TreeSet<String>
        index[token] << id
    }
    return index
}

// [deviceId: id] on a unique match, otherwise [issue: code]. Never resolves by label.
Map resolveDeviceToken(Object token, Map<String, Set<String>> parentIndex) {
    if (parentIndex == null) return [issue: 'missing-parent-index']
    if (!(token instanceof String) || !((token as String) ==~ /^:[0-9a-f]{32}:$/)) return [issue: 'not-a-device-token']
    Set<String> ids = parentIndex[token as String]
    if (!ids) return [issue: 'no-match']
    if (ids.size() > 1) return [issue: 'ambiguous']
    return [deviceId: ids.first()]
}


// Classifies variable references by namespace and derives read/write direction from position.
// Implements device_and_variable_resolution.md sections 6 to 10.

// '$name' system, '@@name' Hub Variable, '@name' webCoRE global, declared plain name local.
// A trailing single index ("list[2]") is stripped first, as webCoRE does.
Map variableIdentity(String raw, Set<String> declaredLocals) {
    String name = raw ?: ''
    if (name && !name.startsWith('$') && name.endsWith(']')) {
        List<String> parts = name.substring(0, name.length() - 1).tokenize('[')
        if (parts.size() == 2) name = parts[0]
    }
    if (!name) return null
    if (name.startsWith('$')) return [namespace: 'system', name: name]
    if (name.startsWith('@@')) return name.length() > 2 ? [namespace: 'hub', name: name.substring(2)] : null
    if (name.startsWith('@')) return name.length() > 1 ? [namespace: 'global', name: name] : null
    String local = name.trim().replace(' ', '_')
    return declaredLocals.contains(local) ? [namespace: 'local', name: local] : [namespace: 'undeclared', name: local]
}

// Returns [variables: [[namespace, name, directions]], dynamicWriteTargets: n] in stable order.
Map collectVariableRoles(Map document) {
    Set<String> declared = [] as TreeSet<String>
    (document?.v instanceof List ? document.v as List : []).each { Object d ->
        if (d instanceof Map && (d as Map).n instanceof String && ((d as Map).n as String).trim()) {
            declared << ((d as Map).n as String).trim().replace(' ', '_')
        }
    }
    Map<String, Map> found = [:] as TreeMap
    int dynamicWrites = 0

    Closure add
    add = { Object rawName, String direction ->
        if (rawName instanceof List) { (rawName as List).each { add(it, direction) }; return }
        if (!(rawName instanceof String)) return
        Map id = variableIdentity(rawName as String, declared)
        if (id == null || id.namespace == 'system') return
        String key = "${id.namespace}:${id.name}".toString()
        if (!found[key]) found[key] = id + [directions: [] as TreeSet<String>]
        found[key].directions << direction
    }

    // setVariable("literal", ...) in an expression: only a single string literal names a target.
    Closure staticString = { Object value ->
        Object current = value
        while (current instanceof Map && (current as Map).t == 'expression') {
            Object items = (current as Map).i
            if (!(items instanceof List) || (items as List).size() != 1) return null
            current = (items as List)[0]
        }
        return (current instanceof Map && (current as Map).t == 'string' && (current as Map).v instanceof String) ? (current as Map).v : null
    }

    Closure walk
    walk = { Object value, boolean suppressRead ->
        if (value instanceof List) { (value as List).each { walk(it, false) }; return }
        if (!(value instanceof Map)) return
        Map item = value as Map
        String type = item.t instanceof String ? item.t as String : null
        Object writeTarget = null

        // Task setVariable: first parameter is the destination and is not evaluated.
        if (item.c == 'setVariable' && item.p instanceof List && item.p && (item.p as List)[0] instanceof Map
                && ((item.p as List)[0] as Map).t == 'x') {
            writeTarget = (item.p as List)[0]
            add(((item.p as List)[0] as Map).x, 'write')
        }
        if (type in ['for', 'each']) add(item.x, 'write')
        if (type == 'p') { add(item.dm, 'write'); add(item.dn, 'write') }
        if (type == 'function' && "${item.n ?: ''}".equalsIgnoreCase('setVariable')) {
            Object first = item.i instanceof List && item.i ? (item.i as List)[0] : null
            Object target = staticString(first)
            if (target != null) add(target, 'write') else dynamicWrites++
        }
        if (!suppressRead && item.x != null && type in ['x', 'variable', 'device']) add(item.x, 'read')
        // A variable inside a device list is evaluated to select devices.
        if (item.d instanceof List && type in ['p', 'd', 'action']) (item.d as List).each { add(it, 'read') }

        item.values().each { Object child ->
            if (child instanceof List) (child as List).each { walk(it, it.is(writeTarget)) }
            else if (child instanceof Map) walk(child, child.is(writeTarget))
        }
    }
    walk(document, false)

    return [variables: found.values().collect { [namespace: it.namespace, name: it.name, directions: it.directions as List] },
            dynamicWriteTargets: dynamicWrites]
}


// Extracts direct device reads (with trigger/constraint role) and direct device actions.
// Implements device_and_variable_resolution.md sections 3 to 5 and statement_and_operand_catalogue.md "Comparison vocabulary and ct".

// webCoRE parent getChildComparisons(), pinned revision: 48 trigger and 35 condition comparisons.
List<String> triggerComparisons() {
    return ['arrives', 'becomes_even', 'becomes_odd', 'changes', 'changes_away_from',
            'changes_away_from_any_of', 'changes_to', 'changes_to_any_of', 'does_not_drop',
            'does_not_rise', 'drops', 'drops_below', 'drops_to_or_below', 'enters_range',
            'event_occurs', 'executes', 'exits_range', 'gets', 'gets_any', 'happens_daily_at',
            'receives', 'remains_above', 'remains_above_or_equal_to', 'remains_below',
            'remains_below_or_equal_to', 'remains_even', 'remains_inside_of_range', 'remains_odd',
            'remains_outside_of_range', 'rises', 'rises_above', 'rises_to_or_above', 'stays',
            'stays_any_of', 'stays_away_from', 'stays_away_from_any_of', 'stays_different_than',
            'stays_equal_to', 'stays_even', 'stays_greater_than', 'stays_greater_than_or_equal_to',
            'stays_inside_of_range', 'stays_less_than', 'stays_less_than_or_equal_to', 'stays_not',
            'stays_odd', 'stays_outside_of_range', 'stays_unchanged']
}

List<String> conditionComparisons() {
    return ['changed', 'did_not_change', 'is', 'is_not', 'is_any_of', 'is_not_any_of', 'is_equal_to',
            'is_different_than', 'is_less_than', 'is_less_than_or_equal_to', 'is_greater_than',
            'is_greater_than_or_equal_to', 'is_inside_of_range', 'is_outside_of_range', 'is_even',
            'is_odd', 'was', 'was_not', 'was_any_of', 'was_not_any_of', 'was_equal_to',
            'was_different_than', 'was_less_than', 'was_less_than_or_equal_to', 'was_greater_than',
            'was_greater_than_or_equal_to', 'was_inside_of_range', 'was_outside_of_range', 'was_even',
            'was_odd', 'is_any', 'is_before', 'is_after', 'is_between', 'is_not_between']
}

// 'trigger', 'constraint' or null. Membership decides; a saved ct must agree or be absent.
// subscribeAll can downgrade a trigger comparison to ct 'c', and ct can be stale after an edit,
// so a conflict is unknown, not a winner.
String conditionRole(Map condition) {
    String co = String.valueOf(condition?.co ?: '')
    boolean trigger = triggerComparisons().contains(co)
    if (!trigger && !conditionComparisons().contains(co)) return null
    String byMembership = trigger ? 'trigger' : 'constraint'
    String ct = String.valueOf(condition?.ct ?: '')
    if (!ct) return byMembership
    String byStored = ct == 't' ? 'trigger' : (ct == 'c' ? 'constraint' : null)
    return byStored == byMembership ? byMembership : null
}

// resolveToken: Closure(String token) -> [deviceId: id] or [issue: code] (see 02_device_hash_lookup).
// Returns reads [deviceId, attribute, role], actions [deviceId, commands], issues [code: count].
Map collectDeviceRelationships(Map document, Closure resolveToken) {
    List<Map> reads = []
    List<Map> actions = []
    Map<String, Integer> issues = [:] as TreeMap
    Closure count = { String code -> issues[code] = (issues[code] ?: 0) + 1 }

    Closure deviceIds = { Object dList ->
        List<String> ids = []
        (dList instanceof List ? dList as List : []).each { Object entry ->
            if (!(entry instanceof String)) { count('malformed-device-node'); return }
            String s = entry as String
            if (s ==~ /^:[0-9a-f]{32}:$/) {
                Map resolved = resolveToken(s) as Map
                if (resolved?.deviceId) ids << (resolved.deviceId as String) else count(resolved?.issue ? resolved.issue as String : 'unresolved')
            } else if (s == '$currentEventDevice') {
                count('runtime-selected-device')
            } else if (s.startsWith('@')) {
                count('variable-backed-device-list')
            } else {
                count('non-physical-device')
            }
        }
        return ids
    }

    Closure walk
    walk = { Object value, String role ->
        if (value instanceof List) { (value as List).each { walk(it, role) }; return }
        if (!(value instanceof Map)) return
        Map item = value as Map
        String type = item.t instanceof String ? item.t as String : null

        if (type == 'p') {
            deviceIds(item.d).each { reads << [deviceId: it, attribute: item.a instanceof String ? item.a : null, role: role ?: 'deviceRead'] }
        } else if (type == 'action') {
            List<String> commands = (item.k instanceof List ? item.k as List : []).findResults {
                it instanceof Map && (it as Map).c instanceof String ? (it as Map).c : null
            }
            deviceIds(item.d).each { actions << [deviceId: it, commands: commands] }
        }

        item.each { Object key, Object child ->
            if (key == 'c' && child instanceof List) {
                // A c list holds events under on, conditions everywhere else. Each entry names its own role.
                (child as List).each { Object entry ->
                    String entryType = entry instanceof Map ? String.valueOf((entry as Map).t ?: '') : ''
                    String entryRole = entryType == 'event' ? 'trigger'
                            : (entryType in ['condition', 'group'] ? conditionRole(entry as Map) : role)
                    walk(entry, entryRole)
                }
            } else if (child instanceof List || child instanceof Map) {
                walk(child, role)
            }
        }
    }
    walk(document, null)
    return [reads: reads, actions: actions, issues: issues]
}


// Walks a decoded piston within fixed budgets and reports unrecognised positions as paths only.
// Implements evidence_model_and_fixture_method.md section 3.

Map walkerLimits() {
    return [maxDepth: 100, maxVisits: 250000, maxScalarLength: 65536, maxFindings: 50, maxPathLength: 200]
}

// Keys read at traversal and dispatch sites in the pinned executor. An omitted key costs
// path legibility, never a leaked name: unknown keys become <unknown-key#N>.
List<String> knownKeys() {
    return ['$', 'a', 'c', 'ced', 'cm', 'co', 'cs', 'ct', 'ctp', 'cto', 'd', 'di', 'e', 'ei', 'exp',
            'f', 'fs', 'g', 'i', 'id', 'k', 'l', 'lo', 'lo2', 'lo3', 'm', 'n', 'o', 'ok', 'p',
            'r', 'rn', 'ro', 'ro2', 'rop', 's', 'sm', 'str', 't', 'tcp', 'tep', 'to', 'to2',
            'ts', 'tsp', 'u', 'v', 'vt', 'w', 'wd', 'wt', 'x', 'xi', 'z']
}

// Editor data that is traversed for budgets but never interpreted or reported by content.
List<String> opaqueKeys() {
    return ['data', 'zc']
}

// Returns [status: complete|truncated, truncation: code|null, visits, maxDepthSeen,
//          findings: [[reason, path]], findingsDropped]. Findings never carry a key name or value.
Map walkStructure(Object document, Map limits = walkerLimits()) {
    Set<String> known = knownKeys() as Set<String>
    Set<String> opaque = opaqueKeys() as Set<String>
    Map result = [status: 'complete', truncation: null, visits: 0, maxDepthSeen: 0, findings: [], findingsDropped: 0]

    Closure boundPath = { String path ->
        int limit = limits.maxPathLength as int
        if (path.length() <= limit) return path
        String marker = '<path-elided>'
        int head = ((limit - marker.length()) / 2) as int
        return path.substring(0, head) + marker + path.substring(path.length() - (limit - marker.length() - head))
    }
    Closure finding = { String reason, String path ->
        if (result.findings.size() < (limits.maxFindings as int)) result.findings << [reason: reason, path: boundPath(path)]
        else result.findingsDropped++
    }
    Closure stop = { String code -> if (result.status == 'complete') { result.status = 'truncated'; result.truncation = code } }

    Closure walk
    walk = { Object value, String path, int depth ->
        if (result.status != 'complete') return
        if (depth > (limits.maxDepth as int)) { stop('depth-limit'); return }
        if (++result.visits > (limits.maxVisits as int)) { stop('visit-limit'); return }
        if (depth > result.maxDepthSeen) result.maxDepthSeen = depth

        if (value instanceof Map) {
            int unknownIndex = 0
            // Sorted so placeholder numbering is stable regardless of stored key order.
            (value as Map).keySet().collect { String.valueOf(it) }.sort().each { String key ->
                Object child = (value as Map)[key]
                String childPath
                if (known.contains(key)) {
                    childPath = "${path}.${key}"
                } else if (opaque.contains(key)) {
                    childPath = "${path}.<opaque>"
                    finding('opaque-field', childPath)
                } else {
                    childPath = "${path}.<unknown-key#${unknownIndex++}>"
                    finding('unrecognised-field', childPath)
                }
                walk(child, childPath, depth + 1)
            }
        } else if (value instanceof List) {
            (value as List).eachWithIndex { Object child, int i -> walk(child, "${path}[${i}]", depth + 1) }
        } else if (value instanceof CharSequence && (value as CharSequence).length() > (limits.maxScalarLength as int)) {
            finding('oversize-scalar', path)
        }
    }
    walk(document, '$', 0)
    return result
}


// Renders plain-text flow labels for tasks, conditions and events from saved operands.
// Implements statement_and_operand_catalogue.md and automation_map_application.md section 4.

int maxLabelLength() { return 120 }

Map<String, Integer> supportedConditionComparisons() {
    return [
        changes: 0, is_even: 0, is_odd: 0,
        is: 1, is_not: 1, is_equal_to: 1, is_different_than: 1,
        is_less_than: 1, is_less_than_or_equal_to: 1, is_greater_than: 1,
        is_greater_than_or_equal_to: 1, is_before: 1, is_after: 1,
        changes_to: 1, changes_away_from: 1, rises_above: 1, rises_to_or_above: 1,
        drops_below: 1, drops_to_or_below: 1,
        is_inside_of_range: 2, is_outside_of_range: 2, is_between: 2, is_not_between: 2,
        enters_range: 2, exits_range: 2
    ]
}

Set<String> supportedConditionJoiners() {
    return ['and', 'or', 'xor', 'followed by'] as Set<String>
}

// Allowlist: constant, virtual, variable and argument operands print their saved spelling.
// Every other kind returns '' so the caller falls back instead of guessing.
String operandText(Object operand) {
    if (!(operand instanceof Map)) return ''
    Map o = operand as Map
    Object raw
    switch (String.valueOf(o.t ?: '')) {
        case 'c': raw = o.c; break
        case 'v': raw = o.v; break
        case 'x': raw = o.x; break
        case 'u': raw = o.u; break
        default: return ''
    }
    if (!(raw instanceof CharSequence || raw instanceof Number || raw instanceof Boolean)) return ''
    String text = raw.toString().replaceAll(/[\p{Cntrl}]/, ' ').trim()
    return text.length() <= maxLabelLength() ? text : ''
}

// command(p1, p2) only when every parameter renders; otherwise the bare command name.
String taskLabel(Map task) {
    String command = task?.c instanceof String ? task.c as String : ''
    if (!command) return 'task'
    List params = task.p instanceof List ? task.p as List : []
    List<String> rendered = []
    for (Object p : params) {
        String text = operandText(p)
        if (!text) return command
        rendered << text
    }
    String label = rendered ? "${command}(${rendered.join(', ')})" : command
    return label.length() <= maxLabelLength() ? label : command
}

// deviceNames: token -> display name, supplied after resolution (see 02). Returns '' unless
// every part of the condition, including nested groups, can be named in full.
String conditionText(List conditions, String joiner, Map<String, String> deviceNames, int depth = 0) {
    if (!conditions || depth > 6) return ''
    if (!supportedConditionJoiners().contains(joiner)) return ''
    List<String> parts = []
    for (Object raw : conditions) {
        if (!(raw instanceof Map)) return ''
        Map c = raw as Map
        if (c.t == 'group') {
            String inner = conditionText(c.c instanceof List ? c.c as List : [], String.valueOf(c.o ?: 'and'), deviceNames, depth + 1)
            if (!inner) return ''
            parts << "(${inner})".toString()
            continue
        }
        if (c.t != 'condition') return ''
        String comparison = c.co instanceof String ? c.co as String : ''
        Integer arity = supportedConditionComparisons()[comparison]
        if (arity == null) return ''
        Map lo = c.lo instanceof Map ? c.lo as Map : [:]
        String subject
        if (lo.t == 'p') {
            List tokens = lo.d instanceof List ? lo.d as List : []
            List<String> names = tokens.collect { deviceNames[String.valueOf(it)] }
            if (!tokens || names.any { !it }) return ''
            subject = names.join(', ') + (lo.a ? "'s ${lo.a}" : '')
        } else {
            subject = operandText(lo)
        }
        if (!subject) return ''
        String line = subject + ' ' + comparison.replace('_', ' ')
        if (arity >= 1) {
            if (c.ro == null) return ''
            String value = operandText(c.ro)
            if (!value) return ''
            line += ' ' + value
        }
        if (arity == 2) {
            if (c.ro2 == null) return ''
            String value2 = operandText(c.ro2)
            if (!value2) return ''
            line += ' and ' + value2
        }
        parts << line
    }
    String text = parts.join(" ${joiner} ")
    return text.length() <= maxLabelLength() ? text : ''
}

String eventLabel(Map event) {
    Map lo = event?.lo instanceof Map ? event.lo as Map : [:]
    String name = lo.t == 'p' ? String.valueOf(lo.a ?: '') : (lo.t in ['v', 'x'] ? operandText(lo) : '')
    return name ? "When ${name} changes" : 'When an event fires'
}



Map runReferenceSelfChecks() {
    Map<String, String> checks = [:] as TreeMap<String, String>
    Closure check = { String name, Closure body ->
        try { checks[name] = body() ? 'PASS' : 'FAIL' }
        catch (Exception ignored) { checks[name] = 'FAIL_EXCEPTION' }
    }

    check('01 chunk decoding') {
        Map doc = [v: [], s: [[t: 'do', s: []]]]
        String encoded = JsonOutput.toJson(doc).getBytes('UTF-8').encodeBase64().toString()
        decodePistonChunks([appSettings: [[name: 'chunk:0', value: encoded]]]).document == doc &&
            decodePistonChunks(null).error == 'missing-settings' &&
            decodePistonChunks([appSettings: []]).status == 'not-present'
    }
    check('02 device-token resolution') {
        String token = webcoreDeviceToken('2')
        Map index = buildTokenIndex(['2'])
        token == ':32e164a2e42f34f83c6d5126248c9a8d:' &&
            resolveDeviceToken(token, index) == [deviceId: '2'] &&
            resolveDeviceToken(token, buildTokenIndex(permittedDeviceIds(null))).issue == 'missing-parent-index'
    }
    check('03 variable roles') {
        Map result = collectVariableRoles([v: [[n: 'counter']], s: [[t: 'for', x: 'counter', lo: [t: 'x', x: '@@Start'], s: []]]])
        Map byName = (result.variables as List).collectEntries { Map item -> ["${item.namespace}:${item.name}".toString(), item.directions] }
        byName['local:counter'] == ['write'] && byName['hub:Start'] == ['read']
    }
    check('04 device reads and actions') {
        String one = ':' + ('0' * 31) + '1:'
        String two = ':' + ('0' * 31) + '2:'
        Closure resolver = { String token -> token == one ? [deviceId: '1'] : (token == two ? [deviceId: '2'] : [issue: 'no-match']) }
        Map result = collectDeviceRelationships([s: [
            [t: 'on', c: [[t: 'event', lo: [t: 'p', a: 'motion', d: [one]]]], s: []],
            [t: 'action', d: [two], k: [[c: 'on', p: []]]]
        ]], resolver)
        result.reads == [[deviceId: '1', attribute: 'motion', role: 'trigger']] &&
            result.actions == [[deviceId: '2', commands: ['on']]]
    }
    check('05 bounded walker') {
        Map result = walkStructure([s: [[t: 'do', unknownSyntheticKey: true]]])
        result.status == 'complete' && result.findings == [[reason: 'unrecognised-field', path: '$.s[0].<unknown-key#0>']]
    }
    check('06 flow labels') {
        String token = ':' + ('1' * 32) + ':'
        Map names = [(token): 'Device']
        Map condition = [t: 'condition', co: 'is', lo: [t: 'p', a: 'switch', d: [token]], ro: [t: 'c', c: 'on']]
        conditionText([[t: 'condition', co: 'is', lo: [t: 'p', a: 'switch', d: [token]], ro: [t: 'c', c: 'on']]], 'and', names) == "Device's switch is on" &&
            conditionText([[t: 'condition', co: 'unknown', lo: [t: 'p', a: 'switch', d: [token]], ro: [t: 'c', c: 'on']]], 'and', names) == '' &&
            conditionText([[t: 'condition', co: 'was', lo: [t: 'p', a: 'switch', d: [token]], ro: [t: 'c', c: 'on']]], 'and', names) == '' &&
            liveLabelCoverage([s: [[t: 'if', c: [[t: 'group', o: 'and', c: [condition, condition]]], s: []]]],
                [(token): ['1'] as Set<String>]) == [rendered: 1, fallback: 0]
    }

    return [mode: 'self-check', status: checks.values().every { it == 'PASS' } ? 'PASS' : 'FAIL', checks: checks]
}

Map inspectReferencePiston(Object rawPistonId) {
    Map pistonResponse = fetchReferenceStatusJson(rawPistonId?.toString())
    if (!pistonResponse.ok) return [mode: 'live inspection', status: 'FAIL', error: pistonResponse.error]
    Map pistonIdentity = pistonResponse.data?.installedApp instanceof Map ? pistonResponse.data.installedApp as Map : [:]
    if ("${pistonIdentity.name ?: ''}".trim() != 'webCoRE Piston') {
        return [mode: 'live inspection', status: 'FAIL', error: 'not-webcore-piston']
    }

    Map decoded = decodePistonChunks(pistonResponse.data as Map)
    if (decoded.status != 'complete') {
        return [mode: 'live inspection', status: decoded.status == 'not-present' ? 'NOT_PRESENT' : 'FAIL',
                error: decoded.error ?: 'piston-not-saved']
    }

    Map document = decoded.document as Map
    Map walked = walkStructure(document)
    Map variables = collectVariableRoles(document)
    Map<String, Set<String>> parentIndex = null
    String parentState = 'missing-parent-id'
    String ownParentId = pistonIdentity.parentAppId == null ? '' : pistonIdentity.parentAppId.toString().trim()

    if (ownParentId ==~ /^[0-9]+$/) {
        Map parentResponse = fetchReferenceStatusJson(ownParentId)
        if (parentResponse.ok) {
            Map parentIdentity = parentResponse.data?.installedApp instanceof Map ? parentResponse.data.installedApp as Map : [:]
            if ("${parentIdentity.name ?: ''}".trim() != 'webCoRE') {
                parentState = 'not-webcore-parent'
            } else {
                parentIndex = buildTokenIndex(permittedDeviceIds(parentResponse.data as Map))
                parentState = parentIndex == null ? 'missing-parent-index' : 'complete'
            }
        } else {
            parentState = parentResponse.error as String
        }
    }

    Closure resolver = { String token -> resolveDeviceToken(token, parentIndex) }
    Map devices = collectDeviceRelationships(document, resolver)
    Map labels = liveLabelCoverage(document, parentIndex)
    Map namespaceCounts = [:] as TreeMap
    (variables.variables as List).each { Map variable ->
        String namespace = variable.namespace as String
        namespaceCounts[namespace] = (namespaceCounts[namespace] ?: 0) + 1
    }

    Map metrics = [
        'accounting status': walked.status,
        'device actions': (devices.actions as List).size(),
        'device reads': (devices.reads as List).size(),
        'dynamic write targets': variables.dynamicWriteTargets,
        'label fallbacks': labels.fallback,
        'labels rendered': labels.rendered,
        'parent resolution': parentState,
        'structure findings': (walked.findings as List).size(),
        'structure findings dropped': walked.findingsDropped,
        'structure visits': walked.visits,
        'variables global': namespaceCounts.global ?: 0,
        'variables hub': namespaceCounts.hub ?: 0,
        'variables local': namespaceCounts.local ?: 0,
        'variables undeclared': namespaceCounts.undeclared ?: 0
    ]
    (devices.issues instanceof Map ? devices.issues as Map : [:]).keySet().sort().each { Object code ->
        metrics["device issue ${code}".toString()] = devices.issues[code]
    }

    decoded.document = null
    document = null
    return [mode: 'live inspection', status: walked.status == 'complete' ? 'PASS' : 'PARTIAL', metrics: metrics]
}

Map liveLabelCoverage(Map document, Map<String, Set<String>> parentIndex) {
    int rendered = 0
    int fallback = 0
    Map<String, String> tokenNames = [:]
    (parentIndex ?: [:]).each { String token, Set<String> ids ->
        if (ids?.size() == 1) tokenNames[token] = 'Device'
    }

    Closure countLabel = { String label -> if (label) rendered++ else fallback++ }
    Closure walk
    walk = { Object value ->
        if (value instanceof List) { (value as List).each { walk(it) }; return }
        if (!(value instanceof Map)) return
        Map item = value as Map
        if (item.t == 'group') {
            countLabel(conditionText(item.c instanceof List ? item.c as List : [], String.valueOf(item.o ?: 'and'), tokenNames))
            return
        }
        if (item.t == 'condition') countLabel(conditionText([item], 'and', tokenNames))
        if (item.t == 'event') countLabel(eventLabel(item))
        if (item.t == 'action' && item.k instanceof List) {
            (item.k as List).each { Object task ->
                if (task instanceof Map) countLabel(taskLabel(task as Map))
            }
        }
        item.values().each { Object child -> if (child instanceof Map || child instanceof List) walk(child) }
    }
    walk(document)
    return [rendered: rendered, fallback: fallback]
}
