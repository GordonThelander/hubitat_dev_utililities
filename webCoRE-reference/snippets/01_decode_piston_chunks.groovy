// Reassembles and decodes a piston's saved chunk:N settings into its JSON document.
// Implements webcore_hubitat_architecture_and_saved_piston_reference.md section 5.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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

// ---- self-check ----

Map statusFor(Map document, List<Integer> cuts = []) {
    String encoded = JsonOutput.toJson(document).getBytes('UTF-8').encodeBase64().toString()
    List<Integer> bounds = [0] + cuts.findAll { it > 0 && it < encoded.length() }.sort().unique() + [encoded.length()]
    List settings = (0..<(bounds.size() - 1)).collect { int i ->
        [name: "chunk:${i}", type: 'text', value: encoded.substring(bounds[i], bounds[i + 1])]
    }
    return [appSettings: settings + [[name: 'unrelated', value: 'x']]]
}

Map piston = [v: [], s: [[t: 'if', o: 'and', c: [], s: [], ei: [], e: []]]]

Map one = decodePistonChunks(statusFor(piston))
assert one.status == 'complete' && one.document == piston
assert decodePistonChunks(statusFor(piston, [5, 17, 40])).document == piston
assert decodePistonChunks([appSettings: []]).status == 'not-present'
assert decodePistonChunks(null).error == 'missing-settings'
assert decodePistonChunks([:]).error == 'missing-settings'
assert decodePistonChunks([appSettings: null]).error == 'missing-settings'
assert decodePistonChunks([appSettings: [:]]).error == 'unexpected-settings'

Map gap = statusFor(piston, [5, 17]); gap.appSettings.remove(1)
assert decodePistonChunks(gap).error == 'missing-chunk'
Map dup = statusFor(piston); dup.appSettings << new LinkedHashMap(dup.appSettings[0])
assert decodePistonChunks(dup).error == 'duplicate-chunk'
assert decodePistonChunks([appSettings: [[name: 'chunk:1', value: 'e30=']]]).error == 'missing-chunk-zero'
assert decodePistonChunks([appSettings: [[name: 'chunk:0', value: 'e30='], [name: 'chunk:999', value: 'e30=']]]).error == 'chunk-index-out-of-range'
assert decodePistonChunks([appSettings: [[name: 'chunk:0', value: '']]]).error == 'empty-chunk'
assert decodePistonChunks([appSettings: [[name: 'chunk:0', value: '***']]]).error == 'invalid-base64'
assert decodePistonChunks([appSettings: [[name: 'chunk:0', value: 'not-json'.bytes.encodeBase64().toString()]]]).error == 'invalid-json'
assert decodePistonChunks([appSettings: [[name: 'chunk:0', value: '[]'.bytes.encodeBase64().toString()]]]).error == 'unexpected-root'
assert decodePistonChunks(statusFor(piston), [maxChunkIndex: 255, maxEncodedLength: 10]).error == 'configuration-too-large'
assert decodePistonChunks(statusFor([n: ':%F0%9F%98%80:'], [3, 9])).document.n == '😀'

println '01_decode_piston_chunks: PASS'
