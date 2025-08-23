/**
 * Innr AE 264 Zigbee Dimmable Bulb Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Namespace: calle
 *  Version: 1.0.0
 *  Date: 2025-08-23
 *
 *  Description:
 *  Native Hubitat Zigbee driver for Innr AE 264 (E26 dimmable white) bulbs.
 *  Provides on/off, level control, standard reporting, health check,
 *  and full support for Power-On Behavior (StartUpOnOff) with read-back.
 *
 *  Changelog:
 *  1.0.0 - Initial release with StartUpOnOff preference, command, and dashboard-friendly attributes
 */

metadata {
    definition(name: "Innr AE 264 Dimmable Bulb", namespace: "calle", author: "Carl Rådetorp") {
        capability "Actuator"
        capability "Sensor"               // for Dashboard Attribute tiles
        capability "Switch"
        capability "Switch Level"
        capability "Refresh"
        capability "Configuration"
        capability "Health Check"

        attribute "Status", "string"
        attribute "powerOnBehavior", "string"     // text: Off/On/Last State/Bulb Default
        attribute "powerOnBehaviorCode", "number" // numeric: 0/1/2/255

        command "configure"
        command "ping"
        command "setPowerOnBehavior"
        command "refreshPowerOnBehavior"

        // Fingerprint for Innr AE 264 (dimmable, no color cluster 0x0300)
        fingerprint profileId: "0104",
                    inClusters: "0000,0003,0004,0005,0006,0008",
                    outClusters: "0019,000A",
                    manufacturer: "Innr",
                    model: "AE 264"
    }

    preferences {
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "transitionTime", type: "number", title: "Transition Time (seconds)", defaultValue: 1, range: "0..10"
        input name: "offlineTimeout", type: "number", title: "Timeout for offline status (minutes)", defaultValue: 10, range: "1..120"
        input name: "powerOnBehavior", type: "enum", title: "Power-On Behavior",
              options: ["0":"Off", "1":"On", "2":"Last State", "255":"Bulb Default"],
              defaultValue: "255"
    }
}

// ---- Helpers ----

private String startUpOnOffToText(Integer val) {
    switch (val) {
        case 0: return "Off"
        case 1: return "On"
        case 2: return "Last State"
        case 255: return "Bulb Default"
        default: return "Unknown (" + val + ")"
    }
}

private void emitPowerOnBehavior(Integer code) {
    def text = startUpOnOffToText(code)
    sendEvent(name: "powerOnBehavior", value: text, descriptionText: "Power-On Behavior: ${text}", isStateChange: true)
    sendEvent(name: "powerOnBehaviorCode", value: code, descriptionText: "Power-On Behavior code: ${code}", isStateChange: true)
    if (debugLogging) log.debug "Emitted Power-On Behavior -> ${code} (${text})"
}

// ---- Lifecycle ----

def installed() {
    log.debug "Installed"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "level", value: 0)
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
    sendEvent(name: "Status", value: device.currentValue("Status") ?: "Online")
    if (debugLogging) runIn(1800, "logsOff")
    configure()
    scheduleHealthCheck()
}

def logsOff() {
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
    log.warn "Debug logging disabled"
}

// ---- Parse ----

def parse(String description) {
    if (debugLogging) log.debug "Parse: ${description}"
    def evt = zigbee.getEvent(description)
    if (evt) {
        if (debugLogging) log.debug "Event: $evt"
        sendEvent(evt)
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

    // Also parse raw read attributes for StartUpOnOff
    def descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.cluster in ["0006", "6"]) {
        def attr = descMap.attrId ?: descMap.attrInt
        if ("4003".equalsIgnoreCase(attr?.toString())) {
            def rawVal = descMap.value
            try {
                Integer intVal = Integer.parseInt(rawVal, 16)
                emitPowerOnBehavior(intVal)
                if (debugLogging) log.debug "Parsed StartUpOnOff ${rawVal} -> ${intVal}"
            } catch (e) {
                if (debugLogging) log.warn "Unable to parse StartUpOnOff value: ${rawVal} (${e})"
            }
        }
    }
}

// ---- Commands ----

def on() {
    if (debugLogging) log.debug "on()"
    def curLevel = device.currentValue("level")
    def useLevel = (state.lastLevel && state.lastLevel != 0) ? state.lastLevel : 100
    sendEvent(name: "switch", value: "on")
    if (!curLevel || curLevel == 0) {
        sendEvent(name: "level", value: useLevel)
        zigbee.setLevel(useLevel)
    } else {
        zigbee.on()
    }
}

def off() {
    if (debugLogging) log.debug "off()"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "level", value: 0)
    zigbee.off()
}

def setLevel(level, duration = null) {
    def time = (duration != null) ? duration : transitionTime
    def safeTime = (time instanceof Number) ? time : (time?.isNumber() ? time.toBigDecimal() : 1)
    def rate = Math.round(safeTime * 10)
    if (debugLogging) log.debug "setLevel(${level}) with transitionTime: ${rate} deciseconds"
    sendEvent(name: "level", value: level)
    if (level == 0) {
        sendEvent(name: "switch", value: "off")
    } else {
        sendEvent(name: "switch", value: "on")
        state.lastLevel = level
    }
    zigbee.setLevel(level, rate)
}

def refresh() {
    if (debugLogging) log.debug "refresh()"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    return zigbee.onOffRefresh() +
           zigbee.levelRefresh() +
           zigbee.readAttribute(0x0006, 0x4003)   // StartUpOnOff (Power-On Behavior)
}

def configure() {
    if (debugLogging) log.debug "configure() - setting reporting and bindings"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    def cmds = zigbee.onOffConfig() +
               zigbee.levelConfig()
    cmds += setPowerOnBehavior()
    return cmds
}

def setPowerOnBehavior(value = null) {
    def setting = value ?: (powerOnBehavior ?: "255")
    if (debugLogging) log.debug "Setting Power-On Behavior to ${setting}"
    // Cluster 0x0006 On/Off, Attribute 0x4003 StartUpOnOff (enum8 / 0x30)
    def cmds = []
    cmds += zigbee.writeAttribute(0x0006, 0x4003, 0x30, Integer.parseInt(setting as String))
    // read-back so tiles update promptly
    cmds += zigbee.readAttribute(0x0006, 0x4003)
    return cmds
}

def refreshPowerOnBehavior() {
    if (debugLogging) log.debug "refreshPowerOnBehavior()"
    return zigbee.readAttribute(0x0006, 0x4003)
}

def ping() {
    if (debugLogging) log.debug "Ping (health check) sent"
    zigbee.readAttribute(0x0006, 0x00)
}

// ---- Health ----

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
