/*
 *  Xiaomi Aqara Cube (MFKZQ01LM) – Minimal Hubitat Driver
 *  -------------------------------------------------------
 *  Purpose: Handle basic gestures reported via Zigbee cluster 0x0012 attr 0x0055.
 *
 *  Notes:
 *  - This driver maps the integer value of 0x0012/0x0055 into button events.
 *  - Because Xiaomi's cube encodes many gestures, exact codes can vary by firmware.
 *    The mapping below is a sensible default and can be customized in preferences.
 *  - All gestures are emitted as `pushed` on different button numbers, so automations
 *    can be built with Hubitat's Button Controller.
 *
 *  Gesture map (default):
 *    0  -> button 1  (shake)
 *    1  -> button 2  (wakeup/alert)
 *    2  -> button 3  (flip 90)
 *    3  -> button 4  (flip 180)
 *    4  -> button 5  (slide)
 *    5  -> button 6  (tap)
 *    6  -> button 7  (rotate cw)
 *    7  -> button 8  (rotate ccw)
 *  Unknown values are emitted on button 99 for debugging.
 *
 *  You can override this by providing a JSON map in preferences, e.g.:
 *    {"0":1, "2":3, "3":4, "4":5, "5":6, "6":7, "7":8}
 *
 *  Copyright 2025
 */

import groovy.transform.Field

metadata {
    definition(name: "Xiaomi Aqara Cube (Minimal)", namespace: "calle", author: "Carl Rådetorp") {
        capability "Sensor"
        capability "PushableButton"
        capability "Configuration"
        capability "Initialize"

        attribute  "lastGestureCode", "number"
        attribute  "lastGestureName", "string"

        command    "resetDebugCounter"

        fingerprint profileId: "0104", endpointId: "02", inClusters: "0000,0003,0019,0012", outClusters: "0000,0004,0003,0005", manufacturer: "LUMI", model: "lumi.sensor_cube", deviceJoinName: "Xiaomi Aqara Cube"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0019,0012", outClusters: "0000,0004,0003,0005", manufacturer: "LUMI", model: "lumi.sensor_cube", deviceJoinName: "Xiaomi Aqara Cube"
    }

    preferences {
        input name: "descLogging", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "customMapJson", type: "string", title: "Custom gesture→button map (JSON)", description: 'e.g. {"0":1, "2":3, "3":4}', required: false
        input name: "unknownAsEvent", type: "bool", title: "Emit unknown codes as button 99 events", defaultValue: true
    }
}

@Field static final Map<Integer, Map> DEFAULT_GESTURES = [
    0: [btn:1, name:'shake'],
    1: [btn:2, name:'wakeup'],
    2: [btn:3, name:'flip90'],
    3: [btn:4, name:'flip180'],
    4: [btn:5, name:'slide'],
    5: [btn:6, name:'tap'],
    6: [btn:7, name:'rotate_cw'],
    7: [btn:8, name:'rotate_ccw']
]

@Field static final Integer FALLBACK_BTN = 99

void installed() {
    logInfo "Installed"
    sendEvent(name: "numberOfButtons", value: 100)
}

void updated() {
    logInfo "Preferences updated"
    sendEvent(name: "numberOfButtons", value: 100)
}

void initialize() {
    logInfo "Initialize"
}

void configure() {
    logInfo "Configure (binding/reporting is minimal; Xiaomi often ignores)"
    // Xiaomi devices can be temperamental with binds; we primarily parse reports.
}

private Map<Integer,Integer> customMap() {
    if (!settings?.customMapJson) return [:]
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(settings.customMapJson as String)
        Map<Integer,Integer> m = [:]
        parsed?.each { k, v ->
            Integer keyInt = (k instanceof Number) ? (k as Integer) : Integer.parseInt(k as String)
            Integer valInt = (v as Integer)
            m[keyInt] = valInt
        }
        return m
    } catch (e) {
        logWarn "Invalid customMapJson: $e"
        return [:]
    }
}

private Map<Integer, Map> effectiveMap() {
    Map<Integer, Map> eff = [:]
    // Start with defaults
    DEFAULT_GESTURES.each { k, v -> eff[k] = v.clone() as Map }
    // Apply overrides for button numbers if provided
    customMap().each { code, btnNum ->
        Map cur = eff[code] ?: [name:"code_${code}"]
        cur.btn = btnNum
        eff[code] = cur
    }
    return eff
}

/**
 * Main parse entry
 */
void parse(String description) {
    if (debugLogging) log.debug "parse: $description"
    if (!description?.startsWith('read attr -') && !description?.startsWith('catchall:') && !description?.startsWith('profile:') && !description?.startsWith('raw:')) {
        // Hubitat often gives key:value string; also handle newer map version
    }
    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (debugLogging) log.debug "descMap: ${descMap}"

    // We're interested in cluster 0x0012, attr 0x0055
    Integer clusterInt = safeHexToInt(descMap?.cluster)
    Integer attrInt    = safeHexToInt(descMap?.attrId)
    Integer cmdInt     = safeHexToInt(descMap?.command)

    if (clusterInt == 0x0012 && attrInt == 0x0055) {
        Integer valueInt = safeHexToInt(descMap?.value)
        handleGesture(valueInt, descMap)
        return
    }

    if (debugLogging) log.debug "Unhandled message: ${descMap}"
}

private void handleGesture(Integer code, Map raw) {
    Map<Integer, Map> map = effectiveMap()
    Map entry = map[code]

    sendEvent(name: 'lastGestureCode', value: code)
    sendEvent(name: 'lastGestureName', value: (entry?.name ?: "code_${code}"))

    Integer btn = entry?.btn
    if (btn) {
        doPush(btn, "${entry.name}")
    } else if (unknownAsEvent) {
        doPush(FALLBACK_BTN, "unknown_${code}")
    }

    if (descLogging) logInfo "Gesture code ${code} → ${(btn ?: FALLBACK_BTN)} (${entry?.name ?: 'unknown'})"
}

private void doPush(Integer buttonNumber, String reason) {
    // Hubitat Button capability: sendEvent name: "pushed" with value = buttonNumber (number)
    def evt = [name: 'pushed', value: buttonNumber, isStateChange: true, type: 'physical', descriptionText: "Button ${buttonNumber} pushed (${reason})"]
    sendEvent(evt)
}

private Integer safeHexToInt(Object hex) {
    try {
        if (hex == null) return null
        String s = hex.toString()
        if (s.startsWith('0x')) s = s.substring(2)
        return Integer.parseInt(s, 16)
    } catch (e) {
        return null
    }
}

void resetDebugCounter() {
    logInfo "resetDebugCounter: (placeholder)"
}

private void logInfo(msg)  { if (descLogging) log.info  "${device.displayName ?: device.name}: ${msg}" }
private void logWarn(msg)  { log.warn  "${device.displayName ?: device.name}: ${msg}" }
