import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Isolated read-only discovery prototype.
 *
 * Mirrors webCoRE's Hubitat storage path:
 *   contiguous chunk:N settings -> concatenate -> Base64 UTF-8 -> emoji decode -> JSON
 */
class WebcoreVariableDecoder {
    static Map decode(Map statusJson) {
        Map<Integer, String> chunks = [:]
        Set<Integer> duplicates = [] as Set

        (statusJson?.appSettings instanceof List ? statusJson.appSettings : []).each { entry ->
            if (!(entry instanceof Map)) return
            def match = ("${entry.name ?: ''}" =~ /^chunk:(\d+)$/)
            if (!match.matches()) return
            int index = match[0][1] as int
            if (chunks.containsKey(index)) duplicates << index
            chunks[index] = entry.value == null ? null : "${entry.value}"
        }

        if (duplicates) return failure('duplicate-chunk', "Duplicate chunk indices: ${duplicates.sort()}")
        if (!chunks.containsKey(0)) return failure('missing-chunk-zero', 'chunk:0 is absent')

        int maximum = chunks.keySet().max() as int
        List<Integer> missing = (0..maximum).findAll { !chunks.containsKey(it) }
        if (missing) return failure('missing-chunk', "Missing chunk indices: ${missing}")
        if ((0..maximum).any { chunks[it] == null || chunks[it].isEmpty() }) {
            return failure('empty-chunk', 'One or more chunks are empty')
        }

        String encoded = (0..maximum).collect { chunks[it] }.join('')
        byte[] decodedBytes
        try {
            decodedBytes = encoded.decodeBase64()
        } catch (Exception ex) {
            return failure('invalid-base64', ex.message ?: 'Base64 decoding failed')
        }

        String jsonText = decodeEmoji(new String(decodedBytes, StandardCharsets.UTF_8))
        def document
        try {
            document = new JsonSlurper().parseText(jsonText)
        } catch (Exception ex) {
            return failure('invalid-json', ex.message ?: 'JSON parsing failed')
        }
        if (!(document instanceof Map)) return failure('unexpected-root', 'Decoded JSON root is not an object')

        Map references = extractReferences(document as Map)
        return [
            ok: true,
            chunkCount: maximum + 1,
            encodedLength: encoded.length(),
            decodedLength: decodedBytes.length,
            hubVariables: references.hubVariables,
            webcoreGlobals: references.webcoreGlobals,
            localVariables: references.localVariables,
            unresolvedVariableOperands: references.unresolvedVariableOperands,
            document: document,
        ]
    }

    static Map extractReferences(Map document) {
        Set<String> declaredLocals = [] as LinkedHashSet
        (document.v instanceof List ? document.v : []).each { definition ->
            if (definition instanceof Map && definition.n != null && "${definition.n}") {
                declaredLocals << sanitizeLocalName("${definition.n}".toString())
            }
        }

        Set<String> hub = [] as LinkedHashSet
        Set<String> globals = [] as LinkedHashSet
        Set<String> locals = [] as LinkedHashSet
        Set<String> unresolved = [] as LinkedHashSet
        List pending = [document]

        while (pending) {
            def value = pending.remove(pending.size() - 1)
            if (value instanceof Map) {
                Map item = value as Map
                if (item.t == 'x' && item.x instanceof String) {
                    String operandName = item.x as String
                    String name = baseVariableName(operandName)
                    if (name.startsWith('@@') && name.length() > 2) {
                        hub << name.substring(2)
                    } else if (name.startsWith('@') && name.length() > 1) {
                        globals << sanitizeLocalName(name)
                    } else if (declaredLocals.contains(sanitizeLocalName(name))) {
                        locals << sanitizeLocalName(name)
                    } else if (!name.startsWith('$')) {
                        unresolved << sanitizeLocalName(name)
                    }
                }
                pending.addAll(item.values().findAll { it instanceof Map || it instanceof List })
            } else if (value instanceof List) {
                pending.addAll((value as List).findAll { it instanceof Map || it instanceof List })
            }
        }

        return [
            hubVariables: (hub as List).sort(),
            webcoreGlobals: (globals as List).sort(),
            localVariables: (locals as List).sort(),
            unresolvedVariableOperands: (unresolved as List).sort(),
        ]
    }

    static String decodeEmoji(String value) {
        if (!value) return ''
        return value.replaceAll(/(\:%[0-9A-F]{2}%[0-9A-F]{2}%[0-9A-F]{2}%[0-9A-F]{2}\:)/) { match ->
            String token = (match instanceof List ? match[0] : match) as String
            URLDecoder.decode(token.substring(1, 13), 'UTF-8')
        }
    }

    static String baseVariableName(String name) {
        if (name && !name.startsWith('$') && name.endsWith(']')) {
            List<String> parts = name.substring(0, name.length() - 1).tokenize('[')
            if (parts.size() == 2) return parts[0]
        }
        return name
    }

    static String sanitizeLocalName(String name) {
        name ? name.trim().replace(' ', '_') : ''
    }

    private static Map failure(String code, String detail) {
        [ok: false, error: code, detail: detail]
    }
}

Map fixtureStatus(Map document, List<Integer> cutPoints = []) {
    String encoded = JsonOutput.toJson(document).getBytes(StandardCharsets.UTF_8).encodeBase64().toString()
    List<Integer> chunks = ([0] + cutPoints.findAll { it > 0 && it < encoded.length() }.sort().unique() + [encoded.length()])
    List settings = []
    for (int index = 0; index < chunks.size() - 1; index++) {
        settings << [
            name: "chunk:${index}",
            type: 'text',
            value: encoded.substring(chunks[index], chunks[index + 1]),
        ]
    }
    [appSettings: settings]
}

Map fixture = [
    n: 'Fixture',
    v: [[n: 'localCounter', t: 'integer'], [n: 'local List', t: 'integer[]']],
    s: [[
        t: 'if',
        c: [
            [t: 'condition', lo: [t: 'x', x: '@@HubShared', f: 'l']],
            [t: 'condition', lo: [t: 'x', x: '@WebcoreShared', f: 'l']],
            [t: 'condition', lo: [t: 'x', x: 'localCounter', f: 'l']],
            [t: 'condition', lo: [t: 'x', x: '@@HubList[1]', f: 'l']],
            [t: 'condition', lo: [t: 'x', x: '@WebcoreList[0]', f: 'l']],
            [t: 'condition', lo: [t: 'x', x: 'local List[2]', f: 'l']],
            [t: 'condition', lo: [t: 'x', x: '$time', f: 'l']],
            [t: 'condition', lo: [t: 'x', x: 'unknownName', f: 'l']],
            [t: 'condition', lo: [t: 'c', c: 'ordinary text containing @@NotAReference']],
        ],
    ]],
]

Map one = WebcoreVariableDecoder.decode(fixtureStatus(fixture))
assert one.ok
assert one.chunkCount == 1
assert one.hubVariables == ['HubList', 'HubShared']
assert one.webcoreGlobals == ['@WebcoreList', '@WebcoreShared']
assert one.localVariables == ['localCounter', 'local_List']
assert one.unresolvedVariableOperands == ['unknownName']

Map many = WebcoreVariableDecoder.decode(fixtureStatus(fixture, [1, 7, 43, 101]))
assert many.ok
assert many.chunkCount == 5
assert many.document == one.document
assert many.hubVariables == one.hubVariables

Map missing = fixtureStatus(fixture, [7, 43])
missing.appSettings.remove(1)
assert WebcoreVariableDecoder.decode(missing).error == 'missing-chunk'

Map duplicate = fixtureStatus(fixture)
duplicate.appSettings << new LinkedHashMap(duplicate.appSettings[0])
assert WebcoreVariableDecoder.decode(duplicate).error == 'duplicate-chunk'

assert WebcoreVariableDecoder.decode([appSettings: [[name: 'chunk:0', value: '***']]]).error == 'invalid-base64'
String nonJson = 'not-json'.bytes.encodeBase64().toString()
assert WebcoreVariableDecoder.decode([appSettings: [[name: 'chunk:0', value: nonJson]]]).error == 'invalid-json'
assert WebcoreVariableDecoder.decode([appSettings: [[name: 'chunk:1', value: 'e30=']]]).error == 'missing-chunk-zero'

Map emoji = [n: ':%F0%9F%98%80:', v: [], s: []]
Map emojiResult = WebcoreVariableDecoder.decode(fixtureStatus(emoji, [3, 11]))
assert emojiResult.ok
assert emojiResult.document.n == '😀'

println 'webCoRE decoder prototype: 9 checks passed'
