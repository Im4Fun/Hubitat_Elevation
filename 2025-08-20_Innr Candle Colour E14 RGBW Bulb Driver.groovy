/**
 * Innr Candle Colour Zigbee RGBW Bulb Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Namespace: calle
 *  Version: 1.1.0
 *  Date: 2025-08-20
 *
 *  Description:
 *  Native Hubitat Zigbee driver for Innr RB 250 C and RB 251 C E14 candle colour RGBW bulbs.
 *
 *  Additions in 1.1.0:
 *  - Optional pre-staging: set level/color/CT without turning the bulb on
 *  - Power-on behavior (ZCL On/Off cluster attr 0x4003): Off, On, or Last State
 *
 *  Notes:
 *  - setColor/setColorTemperature no longer force on() when pre-staging is enabled
 *  - setLevel uses a non-On/Off Zigbee command (Move to Level, 0x00) when pre-staging is enabled
 *  - When pre-staging is disabled, behavior matches built-in logic (commands may turn the bulb on)
 */

import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: "Innr Candle Colour E14 RGBW Bulb", namespace: "calle", author: "Carl Rådetorp") {
        capability "Actuator"
        capability "Switch"
        capability "Switch Level"
        capability "Color Control"
        capability "Color Temperature"
        capability "Refresh"
        capability "Configuration"
        capability "Health Check"

        attribute "colorMode", "string"
        attribute "Status", "string"

        command "configure"
        command "ping"

        fingerprint profileId: "0104",
                    inClusters: "0000,0003,0004,0005,0006,0008,0300",
                    outClusters: "0019,000A",
                    manufacturer: "Innr",
                    model: "RB 250 C"
        fingerprint profileId: "0104",
                    inClusters: "0000,0003,0004,0005,0006,0008,0300",
                    outClusters: "0019,000A",
                    manufacturer: "Innr",
                    model: "RB 251 C"
    }

    preferences {
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "transitionTime", type: "number", title: "Transition Time (seconds)", defaultValue: 1, range: "0..10"
        input name: "offlineTimeout", type: "number", title: "Timeout for offline status (minutes)", defaultValue: 10, range: "1..120"
        input name: "enablePreStaging", type: "bool", title: "Enable color/level pre-staging (don’t turn on when setting)", defaultValue: false
        input name: "sceneCompatSetColorOn", type: "bool", title: "Room Lighting compatibility: setColor turns bulb on", defaultValue: true
        input name: "sceneCompatCTOn", type: "bool", title: "Room Lighting compatibility: setColorTemperature turns bulb on", defaultValue: true
        input name: "powerOnBehavior", type: "enum", title: "Power-On Behavior (on mains restore)", options: ["0":"Off", "1":"On", "2":"Last State"], defaultValue: "2"
    }
}

// ========================= Lifecycle =========================

def installed() {
    log.debug "Installed"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "level", value: 0)
    sendEvent(name: "colorMode", value: "RGB")
    sendEvent(name: "Status", value: "Online")
    state.lastLevel = 100
    state.lastCheckin = now()
    configure()
    scheduleHealthCheck()
}

def updated() {
    log.debug "Updated"
    sendEvent(name: "switch", value: device.currentValue("switch") ?: "off")
    sendEvent(name: "level", value: device.currentValue("level") ?: 0)
    sendEvent(name: "colorMode", value: device.currentValue("colorMode") ?: "RGB")
    sendEvent(name: "Status", value: device.currentValue("Status") ?: "Online")
    if (debugLogging) runIn(1800, "logsOff")
    configure()
    scheduleHealthCheck()
}

def logsOff() {
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
    log.warn "Debug logging disabled"
}

// ========================= Parsing =========================

def parse(String description) {
    if (debugLogging) log.debug "Parse: ${description}"
    def evt = zigbee.getEvent(description)
    if (evt) {
        if (debugLogging) log.debug "Event: $evt"
        sendEvent(evt)
        if (evt.name in ["hue", "saturation"]) {
            sendEvent(name: "colorMode", value: "RGB")
        }
        if (evt.name == "colorTemperature") {
            sendEvent(name: "colorMode", value: "CT")
        }
        if (evt.name == "level") {
            if (evt.value != null && evt.value != 0) state.lastLevel = evt.value
            if (evt.value == 0) sendEvent(name: "switch", value: "off")
            else sendEvent(name: "switch", value: "on")
        }
        if (evt.name == "switch") {
            if (evt.value == "off") sendEvent(name: "level", value: 0)
        }
        sendEvent(name: "Status", value: "Online")
        state.lastCheckin = now()
    } else {
        if (debugLogging) log.debug "Unhandled: $description"
    }
}

// ========================= Commands =========================

def on() {
    if (debugLogging) log.debug "on()"
    def curLevel = device.currentValue("level") as Integer
    def useLevel = (state.lastLevel && state.lastLevel != 0) ? (state.lastLevel as Integer) : 100
    sendEvent(name: "switch", value: "on")
    if (!curLevel || curLevel == 0) {
        sendEvent(name: "level", value: useLevel)
        return zigbee.setLevel(useLevel)
    } else {
        return zigbee.on()
    }
}


def off() {
    if (debugLogging) log.debug "off()"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "level", value: 0)
    return zigbee.off()
}

// Helper: tenths of a second to uint16 hex (little-endian)
private String hex16le(int val) {
    int v = val & 0xFFFF
    String lo = zigbee.convertToHexString(v & 0xFF, 2)
    String hi = zigbee.convertToHexString((v >> 8) & 0xFF, 2)
    return lo + hi
}

// ========================= Level =========================

private Integer clampInt(def v, int lo, int hi) {
    try {
        int x = (v as Integer)
        if (x < lo) return lo
        if (x > hi) return hi
        return x
    } catch (e) {
        return lo
    }
}

def setLevel(level, duration = null) {
    Integer lvl = clampInt(level, 0, 100)
    def time = (duration != null) ? duration : transitionTime
    def safeTime = (time instanceof Number) ? time : (time?.isNumber() ? time.toBigDecimal() : 1)
    int rate = Math.round(safeTime * 10) // deciseconds
    if (debugLogging) log.debug "setLevel(${lvl}) with transitionTime: ${rate} deciseconds (preStaging=${enablePreStaging})"

    sendEvent(name: "level", value: lvl)
    if (lvl == 0) {
        sendEvent(name: "switch", value: "off")
        return zigbee.off()
    } else {
        state.lastLevel = lvl
        if (enablePreStaging) {
            // Move to Level (0x00) — does NOT change On/Off state
            String lvlHex = zigbee.convertToHexString((Math.round(lvl * 2.54) as Integer), 2) // 0-254 scale
            String rateHexLe = hex16le(rate)
            return [zigbee.command(0x0008, 0x00, lvlHex + rateHexLe)]
        } else {
            sendEvent(name: "switch", value: "on")
            return zigbee.setLevel(lvl, rate)
        }
    }
}

// ========================= Color Temperature =========================

def setColorTemperature(temp) {
    Integer ct = (temp as Integer)
    def time = transitionTime
    def safeTime = (time instanceof Number) ? time : (time?.isNumber() ? time.toBigDecimal() : 1)
    int rate = Math.round(safeTime * 10)
    if (debugLogging) log.debug "setColorTemperature(${ct}) with transitionTime: ${rate} deciseconds (preStaging=${enablePreStaging}, sceneCompatCTOn=${sceneCompatCTOn})"

    sendEvent(name: "colorMode", value: "CT")
    // If pre-staging is enabled but Room Lighting compatibility is on, still turn on for CT scenes
    if ((!enablePreStaging) || (sceneCompatCTOn == true)) {
        sendEvent(name: "switch", value: "on")
    }
    return zigbee.setColorTemperature(ct, rate)
}

// ========================= Color (Hue/Sat) =========================

def setColor(Map color) {
    def time = transitionTime
    def safeTime = (time instanceof Number) ? time : (time?.isNumber() ? time.toBigDecimal() : 1)
    int rate = Math.round(safeTime * 10)
    boolean forceOn = (sceneCompatSetColorOn == true) || (!enablePreStaging)
    if (debugLogging) log.debug "setColor(${color}) with transitionTime: ${rate} deciseconds (preStaging=${enablePreStaging}, sceneCompatSetColorOn=${sceneCompatSetColorOn}, forceOn=${forceOn})"

    sendEvent(name: "colorMode", value: "RGB")

    List cmds = []

    Integer lvl = (color.level != null) ? clampInt(color.level, 0, 100) : null
    if (lvl != null) {
        sendEvent(name: "level", value: lvl)
        if (lvl != 0) state.lastLevel = lvl
    }

    if (forceOn) {
        sendEvent(name: "switch", value: "on")
        cmds += zigbee.on()
        if (lvl != null) {
            cmds += zigbee.setLevel(lvl, rate)
        }
    } else if (lvl != null) {
        // Strict pre-staging path: do NOT change on/off state
        String lvlHex = zigbee.convertToHexString((Math.round(lvl * 2.54) as Integer), 2)
        String rateHexLe = hex16le(rate)
        cmds += zigbee.command(0x0008, 0x00, lvlHex + rateHexLe) // Move to Level (no On/Off)
    }

    cmds += zigbee.setColor(color, rate)
    return cmds
}


def setHue(hue) {
    if (debugLogging) log.debug "setHue(${hue})"
    def color = [
        hue: hue,
        saturation: device.currentValue("saturation") ?: 100
    ]
    return setColor(color)
}


def setSaturation(sat) {
    if (debugLogging) log.debug "setSaturation(${sat})"
    def color = [
        hue: device.currentValue("hue") ?: 0,
        saturation: sat
    ]
    return setColor(color)
}

// ========================= Maintenance =========================

def refresh() {
    if (debugLogging) log.debug "refresh()"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    return zigbee.onOffRefresh() +
           zigbee.levelRefresh() +
           zigbee.colorTemperatureRefresh() +
           zigbee.readAttribute(0x0300, 0x00) + // Current Hue
           zigbee.readAttribute(0x0300, 0x01) + // Current Saturation
           zigbee.readAttribute(0x0006, 0x4003)   // StartUpOnOff
}


def configure() {
    if (debugLogging) log.debug "configure() - setting reporting, bindings, and startup behavior"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()

    List cmds = []
    cmds += zigbee.onOffConfig()
    cmds += zigbee.levelConfig()
    cmds += zigbee.colorTemperatureConfig()
    cmds += configureColorReporting()

    // Apply power-on behavior (StartUpOnOff: enum8) if preference is set
    if (powerOnBehavior != null) {
        try {
            int pov = (powerOnBehavior as Integer)
            pov = (pov < 0 ? 0 : (pov > 2 ? 2 : pov))
            if (debugLogging) log.debug "Writing StartUpOnOff (0x4003) = ${pov}"
            cmds += zigbee.writeAttribute(0x0006, 0x4003, DataType.ENUM8, pov)
        } catch (e) {
            log.warn "Invalid powerOnBehavior value: ${powerOnBehavior} (${e})"
        }
    }

    return cmds
}


def configureColorReporting() {
    if (debugLogging) log.debug "Configuring Hue and Saturation reporting"
    def cmds = []
    cmds += zigbee.configureReporting(0x0300, 0x00, 0x20, 1, 3600, 5) // Current Hue
    cmds += zigbee.configureReporting(0x0300, 0x01, 0x20, 1, 3600, 5) // Current Saturation
    return cmds
}


def ping() {
    if (debugLogging) log.debug "Ping (health check) sent"
    return zigbee.readAttribute(0x0006, 0x00)
}

// ========================= Health =========================

def scheduleHealthCheck() {
    unschedule("doHealthCheck")
    schedule("0 */2 * ? * *", "doHealthCheck") // every 2 minutes
}


def doHealthCheck() {
    def timeoutMin = offlineTimeout ?: 10
    def lastSeen = state.lastCheckin ?: now()
    def minsSince = (now() - lastSeen) / 60000
    if (minsSince > timeoutMin) {
        sendEvent(name: "Status", value: "Offline")
        if (debugLogging) log.warn "No contact with bulb for ${minsSince.round()} minutes – marked as offline!"
    }
}
