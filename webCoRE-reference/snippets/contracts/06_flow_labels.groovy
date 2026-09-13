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

// ---- self-check ----

String door = ':' + ('1' * 32) + ':'
String lamp = ':' + ('2' * 32) + ':'
Map names = [(door): 'Patio Door', (lamp): 'Lamp']

assert taskLabel([c: 'setLevel', p: [[t: 'c', vt: 'integer', c: '40']]]) == 'setLevel(40)'
assert taskLabel([c: 'on', p: []]) == 'on'
assert taskLabel([c: 'setVariable', p: [[t: 'x', x: '@@Mode'], [t: 'd', d: [lamp]]]]) == 'setVariable'
assert taskLabel([c: 'log', p: [[t: 'c', c: 'x' * 200]]]) == 'log'
assert taskLabel([c: 'log', p: [[t: 'c', c: [nested: 'map']]]]) == 'log'

List conditions = [
    [t: 'condition', co: 'is', lo: [t: 'p', a: 'contact', d: [door]], ro: [t: 'c', c: 'closed']],
    [t: 'group', o: 'or', c: [
        [t: 'condition', co: 'is_greater_than', lo: [t: 'x', x: '@@Lux'], ro: [t: 'c', c: '50']],
        [t: 'condition', co: 'is', lo: [t: 'p', a: 'switch', d: [lamp]], ro: [t: 'c', c: 'on']]]]]
assert conditionText(conditions, 'and', names) == "Patio Door's contact is closed and (@@Lux is greater than 50 or Lamp's switch is on)"
assert conditionText(conditions, 'and', [(door): 'Patio Door']) == ''
assert conditionText([[t: 'condition', co: 'is', lo: [t: 'e', exp: [:]], ro: [t: 'c', c: '1']]], 'and', names) == ''
assert conditionText([[t: 'condition', co: 'mystery', lo: [t: 'p', a: 'switch', d: [lamp]], ro: [t: 'c', c: 'on']]], 'and', names) == ''
assert conditionText([[t: 'condition', lo: [t: 'p', a: 'switch', d: [lamp]], ro: [t: 'c', c: 'on']]], 'and', names) == ''
assert conditionText([[t: 'condition', co: 'is', lo: [t: 'p', a: 'switch', d: [lamp]]]], 'and', names) == ''
assert conditionText([[t: 'condition', co: 'is', lo: [t: 'p', a: 'switch', d: [lamp]], ro: [t: 'c', c: 'on']]], 'nor', names) == ''
assert conditionText([[t: 'condition', co: 'is_between', lo: [t: 'v', v: 'time'], ro: [t: 'c', c: '08:00']]], 'and', names) == ''
assert conditionText([[t: 'condition', co: 'was', lo: [t: 'p', a: 'switch', d: [lamp]], ro: [t: 'c', c: 'on']]], 'and', names) == ''
assert conditionText([[t: 'condition', co: 'is', lo: [t: 'v', v: 'time'], ro: [t: 'c', c: 'a\nb']]], 'and', names) == 'time is a b'
assert eventLabel([t: 'event', lo: [t: 'p', a: 'motion', d: [door]]]) == 'When motion changes'
assert eventLabel([t: 'event', lo: [t: 's']]) == 'When an event fires'

println '06_flow_labels: PASS'
