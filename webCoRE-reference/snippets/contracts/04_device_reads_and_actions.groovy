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

// ---- self-check ----

Closure token = { String id -> ':' + id.padLeft(32, '0') + ':' }
Map known = ['1', '2', '3', '4', '5'].collectEntries { [token(it), it] }
Closure resolver = { String t -> known[t] ? [deviceId: known[t]] : [issue: 'no-match'] }
Closure p = { String id, String attr -> [t: 'p', vt: 'enum', a: attr, d: [token(id)]] }

Map piston = [s: [
    [t: 'on', c: [[t: 'event', lo: p('1', 'motion')]], s: []],
    [t: 'if', o: 'and', c: [
        [t: 'condition', co: 'is', ct: 'c', lo: p('2', 'switch'), ro: [t: 'c', vt: 'enum', c: 'on']],
        [t: 'condition', co: 'changes_to', lo: p('3', 'contact'), ro: [t: 'c', vt: 'enum', c: 'open']],
        [t: 'condition', co: 'changes', ct: 'c', lo: p('4', 'presence')],
        [t: 'condition', co: 'mystery', lo: p('4', 'battery')],
        [t: 'group', o: 'or', c: [[t: 'condition', co: 'is', lo: p('5', 'level'), ro: [t: 'c', vt: 'integer', c: '10']]]]],
     s: [[t: 'action', d: [token('2'), '$currentEventDevice', '@@Lights', token('99')],
          k: [[c: 'on', p: []], [c: 'setLevel', p: [[t: 'c', vt: 'integer', c: '40']]]]]],
     ei: [], e: []],
    [t: 'action', d: [], k: [[c: 'log', p: [[t: 'e', exp: [t: 'expression', i: []], lo: p('5', 'temperature')]]]]]
]]

Map result = collectDeviceRelationships(piston, resolver)
Map roleOf = result.reads.collectEntries { ["${it.deviceId}.${it.attribute}".toString(), it.role] }

assert roleOf['1.motion'] == 'trigger'
assert roleOf['2.switch'] == 'constraint'
assert roleOf['3.contact'] == 'trigger'
assert roleOf['4.presence'] == 'deviceRead'
assert roleOf['4.battery'] == 'deviceRead'
assert roleOf['5.level'] == 'constraint'
assert roleOf['5.temperature'] == 'deviceRead'
assert result.actions == [[deviceId: '2', commands: ['on', 'setLevel']]]
assert result.issues == ['no-match': 1, 'runtime-selected-device': 1, 'variable-backed-device-list': 1]
assert triggerComparisons().size() == 48 && conditionComparisons().size() == 35

println '04_device_reads_and_actions: PASS'
