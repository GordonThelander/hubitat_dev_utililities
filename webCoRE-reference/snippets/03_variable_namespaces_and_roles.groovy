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

// ---- self-check ----

Map piston = [
    v: [[n: 'counter', t: 'integer'], [n: 'room list', t: 'string[]']],
    s: [
        [t: 'if', c: [
            [t: 'condition', co: 'is', lo: [t: 'x', x: '@@Away'], ro: [t: 'c', vt: 'boolean', c: 'true']],
            [t: 'condition', co: 'is', lo: [t: 'x', x: 'room list[2]'], ro: [t: 'x', x: '@shared']]],
         s: [[t: 'action', d: [], k: [
             [c: 'setVariable', p: [[t: 'x', x: 'counter'], [t: 'x', x: '@@Level']]],
             [c: 'setVariable', p: [[t: 'x', x: '@@Away'], [t: 'c', vt: 'boolean', c: 'false']]]]]]],
        [t: 'for', x: 'counter', lo: [t: 'c', c: '1'], lo2: [t: 'x', x: '$index'], s: []],
        [t: 'action', d: ['@@DeviceHolder'], k: []],
        [t: 'action', d: [], k: [[c: 'log', p: [[t: 'e', exp: [t: 'expression', i: [
            [t: 'function', n: 'setVariable', i: [[t: 'expression', i: [[t: 'string', v: 'counter']]], [t: 'integer', v: 1]]],
            [t: 'function', n: 'setVariable', i: [[t: 'expression', i: [[t: 'variable', x: 'counter'], [t: 'string', v: 'x']]]]]]]]]]]]
    ]
]

Map result = collectVariableRoles(piston)
Map byKey = result.variables.collectEntries { ["${it.namespace}:${it.name}".toString(), it.directions] }

assert byKey['hub:Away'] == ['read', 'write']
assert byKey['hub:Level'] == ['read']
assert byKey['hub:DeviceHolder'] == ['read']
assert byKey['global:@shared'] == ['read']
assert byKey['local:counter'] == ['read', 'write']
assert byKey['local:room_list'] == ['read']
assert !byKey.keySet().any { it.startsWith('system:') }
assert result.dynamicWriteTargets == 1
assert variableIdentity('unknownName', [] as Set).namespace == 'undeclared'
assert collectVariableRoles(piston) == result

println '03_variable_namespaces_and_roles: PASS'
