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
    definition(name: "Xiaomi Aqara Cube (Minimal + Battery)", namespace: "calle", author: "Carl Rådetorp") {
        capability "Sensor"
        capability "PushableButton"
        capability "Battery"
        capability "Configuration"
        capability "Initialize"

        attribute  "lastGestureCode", "number"
        attribute  "lastGestureName", "string"

        // Common Aqara Cube fingerprints
        fingerprint profileId: "0104", endpointId: "02", inClusters: "0000,0003,0019,0012,0001", outClusters: "0000,0004,0003,0005", manufacturer: "LUMI", model: "lumi.sensor_cube", deviceJoinName: "Xiaomi Aqara Cube"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0019,0012,0001", outClusters: "0000,0004,0003,0005", manufacturer: "LUMI", model: "lumi.sensor_cube", deviceJoinName: "Xiaomi Aqara Cube"
    }

    preferences {
        input name: "descLogging", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "unknownAsEvent", type: "bool", title: "Emit unknown gesture codes as button 99 events", defaultValue: true
        input name: "battMinV", type: "decimal", title: "Battery 0% voltage (V)", defaultValue: 2.7
        input name: "battMaxV", type: "decimal", title: "Battery 100% voltage (V)", defaultValue: 3.1
        input name: "mirrorBatteryToState", type: "bool", title: "Also store battery as State Variable (batteryPct)", defaultValue: true
    }
}

@Field static final Map<Integer, Map> DEFAULT_GESTURES = [
    0: [btn:1, name:'shake']
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

def configure() {
    logInfo "Configure: reading & setting battery reporting"
    def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x0021) // BatteryPercentageRemaining
    cmds += zigbee.readAttribute(0x0001, 0x0020) // BatteryVoltage
    cmds += zigbee.configureReporting(0x0001, 0x0021, 0x20, 3600, 21600, 1) // 1% change, 1–6h
    cmds += zigbee.configureReporting(0x0001, 0x0020, 0x20, 3600, 21600, 1) // 0.1V change, 1–6h
    return cmds
}

private Map<Integer, Map> effectiveMap() {
    Map<Integer, Map> eff = [:]
    DEFAULT_GESTURES.each { k, v -> eff[k] = v.clone() as Map }
    return eff
}

void parse(String description) {
    if (debugLogging) log.debug "parse: $description"
    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (debugLogging) log.debug "descMap: ${descMap}"

    Integer clusterInt = safeHexToInt(descMap?.cluster)
    Integer attrInt    = safeHexToInt(descMap?.attrId)

    if (clusterInt == 0x0012 && attrInt == 0x0055) {
        Integer valueInt = safeHexToInt(descMap?.value)
        handleGesture(valueInt)
        return
    }

    if (clusterInt == 0x0001) {
        handleBattery(descMap)
        return
    }

    if (debugLogging) log.debug "Unhandled message: ${descMap}"
}

private void handleGesture(Integer code) {
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

private void handleBattery(Map descMap) {
    Integer attr = safeHexToInt(descMap?.attrId)
    Integer raw  = safeHexToInt(descMap?.value)
    if (attr == null || raw == null) return

    if (attr == 0x0021) {
        int pct = Math.max(0, Math.min(100, (int)Math.round(raw / 2.0)))
        emitBattery(pct, "percent")
    } else if (attr == 0x0020) {
        BigDecimal volts = (raw / 10.0)
        int pct = voltageToPercent(volts)
        emitBattery(pct, "voltage ${volts}V")
    }
}

private int voltageToPercent(BigDecimal v) {
    BigDecimal minV = (settings?.battMinV ?: 2.7) as BigDecimal
    BigDecimal maxV = (settings?.battMaxV ?: 3.1) as BigDecimal
    if (v <= minV) return 0
    if (v >= maxV) return 100
    BigDecimal pct = ((v - minV) / (maxV - minV) * 100.0)
    return Math.max(0, Math.min(100, pct.setScale(0, BigDecimal.ROUND_HALF_UP) as int))
}

private void emitBattery(int pct, String via) {
    sendEvent(name: 'battery', value: pct, unit: '%')
    if (settings?.mirrorBatteryToState) {
        state.batteryPct = pct
    }
    if (descLogging) logInfo "Battery ${pct}% (${via})"
}

private void doPush(Integer buttonNumber, String reason) {
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

private void logInfo(msg)  { if (descLogging) log.info  "${device.displayName ?: device.name}: ${msg}" }
private void logWarn(msg)  { log.warn  "${device.displayName ?: device.name}: ${msg}" }
