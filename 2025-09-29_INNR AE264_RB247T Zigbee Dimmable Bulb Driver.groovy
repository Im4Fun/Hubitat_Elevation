/**
 * INNR AE264 & RB247T Zigbee Bulb Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Namespace: calle
 *  Version: 1.2.0
 *  Date: 2025-09-29
 *
 *  Description:
 *  Native Hubitat Zigbee driver for:
 *   • INNR AE264 (E26 dimmable white)
 *   • INNR RB 247 T (E27 tunable white)
 *
 *  Provides on/off, level control, color temperature (RB 247 T),
 *  standard reporting, Hubitat-style health check (ping + offline status),
 *  and full support for Power-On Behavior (StartUpOnOff) with read-back.
 *
 *  Changelog:
 *  1.0.0 - Initial release with StartUpOnOff preference, command, and dashboard-friendly attributes
 *  1.0.1 - Fix StartUpOnOff mapping: 0xFF = Last State, 0x02 = Toggle
 *  1.1.0 - Add Color Temperature capability + parsing for devices with 0x0300 (tunable white)
 *  1.2.0 - Add fingerprint for INNR RB247T, Touchlink cluster 0x1000, controllerType ZGB;
 *          add reporting for ColorTemperature Mireds; refine health check
 */

import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: "INNR Zigbee Bulb AE264 & RB247T", namespace: "calle", author: "Carl Rådetorp") {
        capability "Actuator"
        capability "Sensor"               // for Dashboard Attribute tiles
        capability "Switch"
        capability "Switch Level"
        capability "Refresh"
        capability "Configuration"
        capability "Health Check"
        capability "Color Temperature"     // for RB247T (0x0300)

        attribute "Status", "string"
        attribute "powerOnBehavior", "string"     // text: Off/On/Toggle/Last State
        attribute "powerOnBehaviorCode", "number" // numeric: 0/1/2/255
        attribute "colorMode", "string"           // 'CT' for tunable white devices

        command "configure"
        command "ping"
        command "setPowerOnBehavior"
        command "refreshPowerOnBehavior"

        // Fingerprint for INNR AE264 (dimmable, no color cluster 0x0300)
        fingerprint profileId: "0104",
                    inClusters: "0000,0003,0004,0005,0006,0008",
                    outClusters: "0019,000A",
                    manufacturer: "INNR",
                    model: "AE264"

        // Fingerprint for INNR RB247T (tunable white)
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,0008,0300,1000",
                    outClusters: "000A,0019",
                    model: "RB247T",
                    manufacturer: "INNR",
                    controllerType: "ZGB"
    }

    preferences {
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "transitionTime", type: "number", title: "Transition Time (seconds)", defaultValue: 1, range: "0..10"
        input name: "offlineTimeout", type: "number", title: "Timeout for offline status (minutes)", defaultValue: 10, range: "1..120"
        input name: "powerOnBehavior", type: "enum", title: "Power-On Behavior",
              options: ["0":"Off", "1":"On", "2":"Toggle", "255":"Last State"],
              defaultValue: "255"
        input name: "ctMinK", type: "number", title: "Min Color Temp (Kelvin) — for tunable bulbs", defaultValue: 2200, range: "1500..7000"
        input name: "ctMaxK", type: "number", title: "Max Color Temp (Kelvin) — for tunable bulbs", defaultValue: 6500, range: "1500..7000"
    }
}

// ---- Helpers ----

private String startUpOnOffToText(Integer val) {
    switch (val) {
        case 0: return "Off"
        case 1: return "On"
        case 2: return "Toggle"
        case 255: return "Last State"
        default: return "Unknown (" + val + ")"
    }
}

private void emitPowerOnBehavior(Integer code) {
    def text = startUpOnOffToText(code)
    sendEvent(name: "powerOnBehavior", value: text, descriptionText: "Power-On Behavior: ${text}", isStateChange: true)
    sendEvent(name: "powerOnBehaviorCode", value: code, descriptionText: "Power-On Behavior code: ${code}", isStateChange: true)
    if (debugLogging) log.debug "Emitted Power-On Behavior -> ${code} (${text})"
}

private Integer clampCTK(Integer k) {
    Integer minK = (ctMinK ?: 2200) as Integer
    Integer maxK = (ctMaxK ?: 6500) as Integer
    return Math.max(minK, Math.min(maxK, (k as Integer)))
}

private Integer kelvinToMired(Integer k) {
    k = clampCTK(k)
    return Math.round(1000000.0 / k)
}

private Integer miredToKelvin(Integer m) {
    if (!m) return null
    return Math.round(1000000.0 / m)
}

private Integer secondsToDs(def seconds) {
    def s = (seconds instanceof Number) ? seconds : (seconds?.isNumber() ? seconds.toBigDecimal() : 1)
    return Math.max(0, Math.round(s * 10))
}

// ---- Lifecycle ----

def installed() {
    log.debug "Installed"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "level", value: 0)
    sendEvent(name: "Status", value: "Online")
    sendEvent(name: "colorMode", value: "CT")
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
    if (device.hasCapability("Color Temperature")) {
        sendEvent(name: "colorMode", value: "CT")
    }
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
        if (debugLogging) log.debug "Unhandled: ${description}"
    }

    // Parse raw attributes
    def descMap = zigbee.parseDescriptionAsMap(description)

    // StartUpOnOff (cluster 0x0006, attr 0x4003)
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

    // Color Temperature (cluster 0x0300, attr 0x0007)
    if (descMap?.cluster in ["0300", "300"]) {
        def attr = descMap.attrId ?: descMap.attrInt
        if ("0007".equalsIgnoreCase(attr?.toString())) {
            try {
                Integer mired = Integer.parseInt(descMap.value, 16)
                Integer k = miredToKelvin(mired)
                if (k) {
                    sendEvent(name: "colorTemperature", value: k, descriptionText: "Color Temperature: ${k}K")
                    sendEvent(name: "colorMode", value: "CT")
                }
            } catch (e) {
                if (debugLogging) log.warn "Unable to parse ColorTemperature: ${descMap.value} (${e})"
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
    def rate = secondsToDs(duration != null ? duration : transitionTime)
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

// --- Color Temperature (RB 247 T) ---

private String le16(int val) {
    String hex = String.format("%04X", val & 0xFFFF)
    // little-endian (low byte first)
    return hex.substring(2,4) + hex.substring(0,2)
}

def setColorTemperature(kelvin, level = null, duration = null) {
    Integer k = clampCTK(kelvin as Integer)
    Integer mired = kelvinToMired(k)
    Integer rate = secondsToDs(duration != null ? duration : transitionTime)
    if (debugLogging) log.debug "setColorTemperature(${k}K => ${mired} mireds) rate=${rate}ds"

    def cmds = []
    // If level is provided, move level too for smoother transitions
    if (level != null) {
        cmds += zigbee.setLevel(level as Integer, rate)
        sendEvent(name: "level", value: level as Integer)
        if ((level as Integer) > 0) sendEvent(name: "switch", value: "on")
    }

    // 0x0300 / 0x0A Move to Color Temperature (payload: mireds LE, transitionTime LE)
    String payload = le16(mired as int) + le16(rate as int)
    cmds += zigbee.command(0x0300, 0x0A, payload)

    // read-back
    cmds += zigbee.readAttribute(0x0300, 0x0007)

    sendEvent(name: "colorMode", value: "CT")
    sendEvent(name: "colorTemperature", value: k)

    return cmds
}

// ---- Refresh / Configure ----

def refresh() {
    if (debugLogging) log.debug "refresh()"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    def cmds = []
    cmds += zigbee.onOffRefresh() + zigbee.levelRefresh()
    cmds += zigbee.readAttribute(0x0006, 0x4003)   // StartUpOnOff (Power-On Behavior)
    // For tunable devices
    cmds += zigbee.readAttribute(0x0300, 0x0007)
    return cmds
}


def configure() {
    if (debugLogging) log.debug "configure() - setting reporting and bindings"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    def cmds = []
    cmds += zigbee.onOffConfig() + zigbee.levelConfig()

    // Color Temperature reporting (if supported)
    cmds += zigbee.configureReporting(0x0300, 0x0007, DataType.UINT16, 5, 600, 50)

    cmds += setPowerOnBehavior()
    return cmds
}


def setPowerOnBehavior(value = null) {
    def setting = value ?: (powerOnBehavior ?: "255")
    if (debugLogging) log.debug "Setting Power-On Behavior to ${setting}"
    def cmds = []
    // Cluster 0x0006 On/Off, Attribute 0x4003 StartUpOnOff (enum8 / 0x30)
    cmds += zigbee.writeAttribute(0x0006, 0x4003, DataType.ENUM8, (setting as Integer))
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
    // Read OnOff; any response marks device online
    return zigbee.readAttribute(0x0006, 0x00)
}

// ---- Health ----

def scheduleHealthCheck() {
    unschedule("doHealthCheck")
    // Hubitat-friendly: run a lightweight check every 2 minutes
    schedule("0 */2 * ? * *", "doHealthCheck")
}


def doHealthCheck() {
    def timeoutMin = (offlineTimeout ?: 10) as Integer
    def lastSeen = state.lastCheckin ?: now()
    def minsSince = (now() - lastSeen) / 60000
    if (minsSince > timeoutMin) {
        sendEvent(name: "Status", value: "Offline")
        if (debugLogging) log.warn "No contact with bulb for ${minsSince.round()} minutes – marked as offline!"
    } else {
        // Opportunistically ping to keep Device Activity
        runIn(1, "ping")
    }
}
