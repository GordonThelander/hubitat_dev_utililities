// Computes webCoRE device tokens and resolves them against one parent's permitted devices.
// Implements device_and_variable_resolution.md section 2.

import java.security.MessageDigest

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

// ---- self-check ----

assert webcoreDeviceToken('2') == ':32e164a2e42f34f83c6d5126248c9a8d:'

Map parent = [appSettings: [
    [name: 'dev', type: 'capability.*', deviceIdsForDeviceList: [2, 17]],
    [name: 'more', type: 'capability.switch', deviceIdsForDeviceList: [17, 40]],
    [name: 'label', type: 'text', value: 'ignored']]]
assert permittedDeviceIds(parent) == ['17', '2', '40']

Map index = buildTokenIndex(permittedDeviceIds(parent))
assert resolveDeviceToken(':32e164a2e42f34f83c6d5126248c9a8d:', index) == [deviceId: '2']
assert resolveDeviceToken(webcoreDeviceToken('999'), index) == [issue: 'no-match']
assert resolveDeviceToken('$currentEventDevice', index) == [issue: 'not-a-device-token']
assert resolveDeviceToken(webcoreDeviceToken('2'), null) == [issue: 'missing-parent-index']
assert resolveDeviceToken(webcoreDeviceToken('2'), buildTokenIndex(permittedDeviceIds(null))) == [issue: 'missing-parent-index']
assert resolveDeviceToken(webcoreDeviceToken('2'), buildTokenIndex(permittedDeviceIds([:]))) == [issue: 'missing-parent-index']
assert resolveDeviceToken(webcoreDeviceToken('2'), buildTokenIndex(permittedDeviceIds([appSettings: []]))) == [issue: 'no-match']

String fixed = ':' + ('a' * 32) + ':'
Map collided = buildTokenIndex(['5', '6'], { String id -> fixed })
assert resolveDeviceToken(fixed, collided) == [issue: 'ambiguous']

println '02_device_hash_lookup: PASS'
