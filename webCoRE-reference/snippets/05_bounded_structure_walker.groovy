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

// ---- self-check ----

Map piston = [
    s: [[t: 'action', d: [], k: [[c: 'httpRequest', p: [[t: 'c', vt: 'string', c: 'x'],
                                                        [t: 'e', exp: [t: 'expression', i: [], secretField: 'hidden']]]]],
         zc: 'user comment', anotherSecret: 1]],
    v: []
]

Map r = walkStructure(piston)
assert r.status == 'complete'
assert r.findings == [
    [reason: 'unrecognised-field', path: '$.s[0].<unknown-key#0>'],
    [reason: 'unrecognised-field', path: '$.s[0].k[0].p[1].exp.<unknown-key#0>'],
    [reason: 'opaque-field', path: '$.s[0].<opaque>']]
assert !r.toString().contains('secret') && !r.toString().contains('hidden') && !r.toString().contains('user comment')

Map deep = [:]; Map cursor = deep
120.times { Map next = [:]; cursor.s = next; cursor = next }
assert walkStructure(deep).truncation == 'depth-limit'
assert walkStructure(piston, walkerLimits() + [maxVisits: 5]).truncation == 'visit-limit'
assert walkStructure([c: 'x' * 70000]).findings == [[reason: 'oversize-scalar', path: '$.c']]
assert walkStructure(piston) == r

println '05_bounded_structure_walker: PASS'
