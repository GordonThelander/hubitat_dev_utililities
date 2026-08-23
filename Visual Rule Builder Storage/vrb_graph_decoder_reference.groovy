/**
 * Conservative, read-only reference decoder for the observed Hubitat Visual
 * Rule Builder 2.0 graphDocument format.
 *
 * Input:
 *   graphDocument - Map containing version, nodes, and edges
 *   deviceLabels  - Map keyed by numeric or string device id
 *
 * Output:
 *   [steps: List<Map>, warnings: List<String>, unknownNodeTypes: List<String>]
 *
 * This is not a Hubitat storage writer or execution engine.
 */
Map decodeVisualRuleBuilderGraph(Map graphDocument, Map deviceLabels = [:]) {
    List warnings = []
    Set unknownTypes = [] as Set
    List nodes = (graphDocument?.nodes instanceof List) ? graphDocument.nodes as List : []
    List edges = (graphDocument?.edges instanceof List) ? graphDocument.edges as List : []

    if (!nodes) return [steps: [], warnings: ['Graph contains no nodes.'], unknownNodeTypes: []]
    if (graphDocument.version != 1) {
        warnings << "Observed decoder was built for graph version 1; received ${graphDocument.version}."
    }

    Map nodesById = [:]
    nodes.each { raw ->
        if (!(raw instanceof Map) || raw.id == null) {
            warnings << 'Ignored a node without a usable id.'
            return
        }
        String id = "${raw.id}"
        if (nodesById.containsKey(id)) warnings << "Duplicate node id: ${id}."
        nodesById[id] = raw as Map
    }

    Map outgoing = [:]
    edges.each { raw ->
        if (!(raw instanceof Map) || raw.from == null || raw.to == null) {
            warnings << 'Ignored an edge without from/to ids.'
            return
        }
        String from = "${raw.from}"
        List list = (outgoing[from] ?: []) as List
        list << [port: "${raw.port ?: ''}", to: "${raw.to}"]
        outgoing[from] = list
    }

    Closure pretty = { String value ->
        String text = value?.replaceAll(/([a-z])([A-Z])/, '$1 $2')?.replaceAll(/[_-]+/, ' ') ?: 'Unknown'
        text ? text[0].toUpperCase() + text.substring(1) : 'Unknown'
    }

    Closure resolveDevices = { Map config ->
        List names = []
        (config ?: [:]).each { key, value ->
            String normal = "${key}".toLowerCase()
            boolean deviceArray = normal == 'switches' || normal.endsWith('sensors') || normal.endsWith('devices')
            if (deviceArray && value instanceof List) {
                (value as List).each { id ->
                    String name = deviceLabels["${id}"] ?: deviceLabels[id]
                    if (name == null) name = "Device ${id}"
                    if (!names.contains("${name}")) names << "${name}"
                }
            }
        }
        names
    }

    Set knownTypes = [
        'contact', 'motion', 'illuminanceCondition', 'turnOn', 'turnOff',
        'wait', 'sendNotification', 'runRule', 'triggerMerge', 'branchMerge'
    ] as Set

    Closure labelForNode = { Map node ->
        String type = "${node.type ?: ''}"
        Map config = (node.config instanceof Map) ? node.config as Map : [:]
        if (!(type in knownTypes)) unknownTypes << type
        switch (type) {
            case 'contact':
            case 'motion':
            case 'illuminanceCondition':
                String stateText = null
                config.each { key, value ->
                    if ("${key}".endsWith('Event') || "${key}".endsWith('State')) stateText = "${value}"
                }
                return stateText ?: pretty(type)
            case 'turnOn': return 'On'
            case 'turnOff': return 'Off'
            case 'wait':
                int minutes = (config.minutes ?: 0) as int
                int seconds = (config.seconds ?: 0) as int
                List parts = []
                if (minutes) parts << "${minutes}m"
                if (seconds) parts << "${seconds}s"
                return "Wait ${parts ? parts.join(' ') : '0s'}"
            case 'sendNotification':
                String message = "${config.notificationMessage ?: ''}"
                return message ? "Notify: ${message}" : 'Notify'
            case 'runRule': return 'Run Rule Actions'
            default: return pretty(type)
        }
    }

    Closure decisionText = { Map node ->
        Map config = (node.config instanceof Map) ? node.config as Map : [:]
        List conditions = (config.conditions instanceof List) ? config.conditions as List : []
        if (!conditions) return pretty("${node.type}")
        String joiner = "${node.type}" == 'any' ? ' OR ' : ' AND '
        conditions.collect { raw ->
            Map condition = (raw instanceof Map) ? raw as Map : [:]
            String text = labelForNode(condition)
            List devices = resolveDevices(condition.config as Map)
            devices ? "${text} on ${devices.join(', ')}" : text
        }.join(joiner)
    }

    Closure decisionDevices = { Map node ->
        Map config = (node.config instanceof Map) ? node.config as Map : [:]
        List conditions = (config.conditions instanceof List) ? config.conditions as List : []
        List names = []
        conditions.each { raw ->
            Map condition = (raw instanceof Map) ? raw as Map : [:]
            resolveDevices(condition.config as Map).each { name -> if (!names.contains(name)) names << name }
        }
        names
    }

    Closure actionStep = { Map node ->
        List targets = ("${node.type}" == 'runRule' && node.config instanceof Map && node.config.appId != null) ?
            ["${node.config.appId}"] : []
        [kind: 'action', label: labelForNode(node), devices: resolveDevices(node.config as Map), ruleTargets: targets]
    }

    List steps = []
    List triggers = nodes.findAll { it instanceof Map && "${it.kind}" == 'trigger' } as List
    triggers.each { Map node ->
        steps << [kind: 'trigger', label: labelForNode(node), devices: resolveDevices(node.config as Map)]
    }
    if (!triggers) return [steps: steps, warnings: warnings + ['No trigger nodes found.'], unknownNodeTypes: unknownTypes.sort()]

    Set nextIds = [] as Set
    triggers.each { Map node ->
        ((outgoing["${node.id}"] ?: []) as List).each { nextIds << "${it.to}" }
    }
    if (nextIds.size() != 1) {
        warnings << "Trigger paths did not converge on exactly one node: ${nextIds as List}."
        return [steps: steps, warnings: warnings, unknownNodeTypes: unknownTypes.sort()]
    }

    String cursor = "${nextIds.iterator().next()}"
    Set visitedMain = [] as Set
    int mainGuard = 0
    while (cursor && mainGuard++ < 200) {
        if (!visitedMain.add(cursor)) {
            warnings << "Cycle detected on main path at ${cursor}."
            break
        }
        Map node = nodesById[cursor] as Map
        if (!node) {
            warnings << "Edge points to missing node ${cursor}."
            break
        }
        List out = (outgoing[cursor] ?: []) as List

        if ("${node.kind}" == 'merge') {
            cursor = out ? "${out[0].to}" : null
            continue
        }

        if ("${node.kind}" == 'decision') {
            steps << [kind: 'action', ctrl: 'if', cond: decisionText(node), label: '', devices: decisionDevices(node)]
            Map trueEdge = out.find { "${it.port}" == 'true' } as Map
            Map falseEdge = out.find { "${it.port}" == 'false' } as Map
            String joinId = null

            Closure walkBranch = { String start, String stopAt ->
                String branchCursor = start
                Set visited = [] as Set
                int branchGuard = 0
                while (branchCursor && branchCursor != stopAt && branchGuard++ < 200) {
                    if (!visited.add(branchCursor)) {
                        warnings << "Cycle detected on branch at ${branchCursor}."
                        break
                    }
                    Map branchNode = nodesById[branchCursor] as Map
                    if (!branchNode) {
                        warnings << "Branch edge points to missing node ${branchCursor}."
                        break
                    }
                    if ("${branchNode.kind}" == 'merge') return branchCursor
                    if ("${branchNode.kind}" == 'decision') {
                        warnings << "Nested decision ${branchCursor} is outside the observed fixture set."
                        break
                    }
                    steps << actionStep(branchNode)
                    List branchOut = (outgoing[branchCursor] ?: []) as List
                    branchCursor = branchOut ? "${branchOut[0].to}" : null
                }
                branchCursor == stopAt ? stopAt : null
            }

            if (trueEdge) joinId = walkBranch("${trueEdge.to}", null)
            if (falseEdge) {
                steps << [kind: 'action', ctrl: 'else', cond: '', label: '', devices: []]
                String falseJoin = walkBranch("${falseEdge.to}", joinId)
                joinId = joinId ?: falseJoin
            }
            steps << [kind: 'action', ctrl: 'endif', cond: '', label: '', devices: []]
            List joinOut = joinId ? ((outgoing[joinId] ?: []) as List) : []
            cursor = joinOut ? "${joinOut[0].to}" : null
            continue
        }

        steps << actionStep(node)
        cursor = out ? "${out[0].to}" : null
    }
    if (mainGuard >= 200) warnings << 'Main path exceeded the 200-node safety bound.'
    unknownTypes.each { warnings << "Unknown node type retained with a generic label: ${it}." }
    [steps: steps, warnings: warnings, unknownNodeTypes: unknownTypes.sort()]
}

