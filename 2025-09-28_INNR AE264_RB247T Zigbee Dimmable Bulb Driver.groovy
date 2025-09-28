/**
 * INNR AE264 & RB247T Zigbee Bulb Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Namespace: calle
 *  Version: 1.1.0
 *  Date: 2025-09-28
 *
 *  Description:
 *  Native Hubitat Zigbee driver for INNR AE264 (E26 dimmable white) and RB247T (tunable white) bulbs.
 *  Provides on/off, level control, color temperature (RB 247 T only), standard reporting, health check,
 *  and full support for Power-On Behavior (StartUpOnOff) with read-back.
 *
 *  Changelog:
 *  1.0.0 - Initial release with StartUpOnOff preference, command, and dashboard-friendly attributes
 *  1.0.1 - Fix StartUpOnOff mapping: 0xFF = Last State, 0x02 = Toggle
 *  1.0.2 - Added fingerprint for INNR RB247T (ZGB controller)
 *  1.0.3 - Updated driver name and description to reflect support for AE 264 & RB 247 T
 *  1.1.0 - Added Color Temperature capability + commands + reporting (cluster 0x0300 attr 0x0007) for RB 247 T
 */

metadata {
    definition(name: "INNR AE264 & RB247T Bulb", namespace: "calle", author: "Carl Rådetorp") {
        capability "Actuator"
        capability "Switch"
        capability "Switch Level"
        capability "Refresh"
        capability "Configuration"
        capability "Health Check"
        capability "Color Temperature"    // RB247T supports Zigbee Color Control cluster (CT only)

        attribute "Status", "string"
        attribute "powerOnBehavior", "string"     // text: Off/On/Toggle/Last State
        attribute "powerOnBehaviorCode", "number" // numeric: 0/1/2/255

        command "configure"
        command "ping"
        command "setPowerOnBehavior"
        command "refreshPowerOnBehavior"
        command "setColorTemperature", [[name: "Color Temperature*", type: "NUMBER", description: "2700-6500K", constraints: [1500, 10000]],
                                          [name: "Level (optional)", type: "NUMBER"],
                                          [name: "Transition (s, optional)", type: "NUMBER"]]

        fingerprint profileId: "0104", deviceId: "0100", deviceVersion: "01", inClusters: "0000,0003,0004,0005,0006,0008,0300", outClusters: "0019", manufacturer: "innr", model: "RB 247 T", deviceJoinName: "INNR RB247T"
        fingerprint profileId: "0104", deviceId: "0100", deviceVersion: "01", inClusters: "0000,0003,0004,0005,0006,0008", outClusters: "0019", manufacturer: "innr", model: "AE 264", deviceJoinName: "INNR AE264"
    }

    preferences {
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "descLogging", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "offlineTimeout", type: "number", title: "Mark Offline after no contact (minutes)", range: "5..120", defaultValue: 10
        input name: "startupBehavior", type: "enum", title: "Power-On Behavior", options: [
            "off": "Off",
            "on": "On",
            "toggle": "Toggle",
            "last": "Last State"
        ], defaultValue: "last"
    }
}

// ---- Utils ----

private Integer clampLevel(Integer lvl) {
    if (lvl == null) return null
    return Math.max(1, Math.min(100, lvl))
}

private Integer levelToZigbee(Integer lvl) {
    lvl = clampLevel(lvl)
    return Math.round(lvl * 254 / 100)
}

private Integer zclToMireds(Integer kelvin) {
    if (!kelvin) return null
    return Math.round(1_000_000 / kelvin)
}

private Integer miredsToKelvin(Integer mireds) {
    if (!mireds) return null
    return Math.round(1_000_000 / mireds)
}

private void descLog(String msg) {
    if (descLogging) log.info msg
}

// ---- Power-On Behavior (StartUpOnOff) ----

private Integer mapStartupTextToCode(String text) {
    switch (text) {
        case "off":   return 0x00
        case "on":    return 0x01
        case "toggle":return 0x02
        case "last":  return 0xFF
        default:      return 0xFF
    }
}

private String mapStartupCodeToText(Integer code) {
    switch (code) {
        case 0x00: return "Off"
        case 0x01: return "On"
        case 0x02: return "Toggle"
        case 0xFF: return "Last State"
        default:   return "Unknown"
    }
}

def setPowerOnBehavior(String behavior = null) {
    behavior = behavior ?: (settings?.startupBehavior ?: "last")
    Integer code = mapStartupTextToCode(behavior)
    if (debugLogging) log.debug "Set StartUpOnOff -> ${code} (${behavior})"
    def cmds = zigbee.writeAttribute(0x0006, 0x4003, 0x30, code)
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

// ---- Lifecycle ----

def installed() {
    log.debug "Installed"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "level", value: 0)
    sendEvent(name: "Status", value: "Online")
    state.lastLevel = 100
    state.lastCheckin = now()
    configure()
    scheduleHealthCheck() // unchanged cadence
}

def updated() {
    log.debug "Updated"
    sendEvent(name: "switch", value: device.currentValue("switch") ?: "off")
    sendEvent(name: "level", value: device.currentValue("level") ?: 0)
    sendEvent(name: "Status", value: device.currentValue("Status") ?: "Online")
    if (debugLogging) runIn(1800, "logsOff")
    configure()
    scheduleHealthCheck() // unchanged cadence
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

    // Raw parse for StartUpOnOff
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
                if (debugLogging) log.warn "Failed to parse StartUpOnOff: ${rawVal} (${e.message})"
            }
        }
    }

    // Raw parse for Color Temperature (cluster 0x0300 attr 0x0007)
    if (descMap?.cluster in ["0300", "300"]) {
        def attr = descMap.attrId ?: descMap.attrInt
        if ("0007".equalsIgnoreCase(attr?.toString())) {
            try {
                Integer mireds = Integer.parseInt(descMap.value, 16)
                Integer kelvin = miredsToKelvin(mireds)
                if (kelvin) {
                    sendEvent(name: "colorTemperature", value: kelvin, unit: "K")
                    if (debugLogging) log.debug "Parsed CT mireds=${mireds} -> ${kelvin}K"
                }
            } catch (e) {
                if (debugLogging) log.warn "Failed to parse colorTemperature: ${e.message}"
            }
        }
    }
}

private void emitPowerOnBehavior(Integer code) {
    def text = mapStartupCodeToText(code)
    sendEvent(name: "powerOnBehaviorCode", value: code)
    sendEvent(name: "powerOnBehavior", value: text, descriptionText: "Power-On Behavior is ${text}")
    if (descLogging) log.debug "Emitted Power-On Behavior -> ${code} (${text})"
}

// ---- Commands ----

def on() {
    descLog "${device.displayName} was turned on"
    state.lastLevel = state.lastLevel ?: 100
    return zigbee.on()
}

def off() {
    descLog "${device.displayName} was turned off"
    sendEvent(name: "level", value: 0)
    return zigbee.off()
}

def setLevel(lvl, dur = null) {
    Integer level = clampLevel(lvl as Integer)
    Integer zLevel = levelToZigbee(level)
    state.lastLevel = level
    if (dur == null) {
        descLog "${device.displayName} level set to ${level}%"
        return zigbee.setLevel(zLevel)
    } else {
        descLog "${device.displayName} level set to ${level}% over ${dur}s"
        return zigbee.setLevel(zLevel, dur as Integer)
    }
}

def refresh() {
    if (debugLogging) log.debug "refresh()"
    def cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x00)  // On/Off
    cmds += zigbee.readAttribute(0x0008, 0x00)  // Level
    cmds += zigbee.readAttribute(0x0006, 0x4003) // StartUpOnOff
    if (device.hasCapability("Color Temperature")) {
        cmds += zigbee.readAttribute(0x0300, 0x0007) // Color Temperature (mireds)
    }
    return cmds
}

def configure() {
    if (debugLogging) log.debug "configure()"
    def cmds = []

    // Bindings
    cmds += zigbee.addBinding(0x0006) // On/Off cluster
    cmds += zigbee.addBinding(0x0008) // Level cluster
    if (device.hasCapability("Color Temperature")) {
        cmds += zigbee.addBinding(0x0300) // Color Control cluster
    }

    // Reporting
    cmds += zigbee.configureReporting(0x0006, 0x00, 0x10, 0, 600, null) // on/off report
    cmds += zigbee.configureReporting(0x0008, 0x00, 0x20, 1, 600, 1)   // level report
    if (device.hasCapability("Color Temperature")) {
        cmds += zigbee.configureReporting(0x0300, 0x0007, 0x21, 5, 600, 5) // CT report (mireds)
    }

    // Read current
    cmds += refresh()

    // Apply startup behavior (write then read back)
    cmds += setPowerOnBehavior(settings?.startupBehavior)
    cmds += "delay 200"
    cmds += refreshPowerOnBehavior()

    return cmds
}

// ---- Color Temperature (RB247T) ----

def setColorTemperature(kelvin, level = null, transition = null) {
    Integer k = kelvin as Integer
    Integer mireds = zclToMireds(k)
    def cmds = []
    if (level != null) {
        cmds += setLevel(level as Integer, (transition ?: 1) as Integer)
    }
    if (transition != null) {
        cmds += zigbee.command(0x0300, 0x0A, zigbee.convertToHexString(mireds, 4), zigbee.convertToHexString(transition as Integer, 4))
    } else {
        cmds += zigbee.writeAttribute(0x0300, 0x0007, 0x21, mireds)
    }
    descLog "${device.displayName} color temperature set to ${k}K"
    return cmds
}

// ---- Health ----

def scheduleHealthCheck() {
    unschedule("healthCheck")
    schedule("0 */2 * ? * *", "healthCheck") // every 2 minutes
}

def healthCheck() {
    if (debugLogging) log.debug "healthCheck()"
    doHealthCheck()
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
