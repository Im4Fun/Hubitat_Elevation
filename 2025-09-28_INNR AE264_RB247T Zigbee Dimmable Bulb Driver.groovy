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
        capability "Sensor"               // for Dashboard Attribute tiles
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
        command "setColorTemperature", [[name: "Color Temperature (K)", type: "NUMBER", description: "2700-6500K", constraints: [1500, 10000]],
                                          [name: "Level (optional)", type: "NUMBER"],
                                          [name: "Transition (s, optional)", type: "NUMBER"]]

        // Fingerprint for INNR AE264 (dimmable, no color cluster 0x0300)
        fingerprint profileId: "0104",
                    inClusters: "0000,0003,0004,0005,0006,0008",
                    outClusters: "0019,000A",
                    manufacturer: "INNR",
                    model: "AE264"

        // Fingerprint for INNR RB247T (tunable white with ZGB controller)
        fingerprint profileId: "0104",
                    endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,0008,0300,1000",
                    outClusters: "000A,0019",
                    manufacturer: "INNR",
                    model: "RB247T",
                    controllerType: "ZGB"
    }

    preferences {
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "transitionTime", type: "number", title: "Transition Time (seconds)", defaultValue: 1, range: "0..10"
        input name: "offlineTimeout", type: "number", title: "Timeout for offline status (minutes)", defaultValue: 10, range: "1..120"
        input name: "powerOnBehavior", type: "enum", title: "Power-On Behavior",
              options: ["0":"Off", "1":"On", "2":"Toggle", "255":"Last State"],
              defaultValue: "255"
        input name: "ctMinK", type: "number", title: "Min Color Temp (K)", defaultValue: 2200, range: "1500..5000"
        input name: "ctMaxK", type: "number", title: "Max Color Temp (K)", defaultValue: 6500, range: "2700..10000"
    }
}

// ---- Helpers ----

private Integer clampCT(Integer k) {
    Integer minK = (settings?.ctMinK as Integer) ?: 2200
    Integer maxK = (settings?.ctMaxK as Integer) ?: 6500
    return Math.max(minK, Math.min(maxK, k))
}

private Integer kelvinToMireds(Integer k) {
    if (!k || k <= 0) return null
    return Math.round(1000000.0 / k)
}

private Integer miredsToKelvin(Integer m) {
    if (!m || m <= 0) return null
    return Math.round(1000000.0 / m)
}

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
                if (debugLogging) log.warn "Unable to parse StartUpOnOff value: ${rawVal} (${e})"
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
                if (debugLogging) log.warn "Unable to parse CT: ${descMap.value} (${e})"
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

// --- Color Temperature ---

def setColorTemperature(kelvin, level = null, duration = null) {
    if (debugLogging) log.debug "setColorTemperature(${kelvin}, level=${level}, duration=${duration})"
    Integer k = clampCT(kelvin as Integer)
    Integer mireds = kelvinToMireds(k)
    def cmds = []
    // Move to CT (0x0300/0x0007). Use writeAttribute for compatibility; some bulbs also accept moveToColorTemperature command 0x0A.
    cmds += zigbee.writeAttribute(0x0300, 0x0007, 0x21, mireds)
    cmds += zigbee.readAttribute(0x0300, 0x0007)
    if (level != null) {
        // Apply optional level change
        def time = (duration != null) ? duration : transitionTime
        def safeTime = (time instanceof Number) ? time : (time?.isNumber() ? time.toBigDecimal() : 1)
        def rate = Math.round(safeTime * 10)
        cmds += zigbee.setLevel(level as Integer, rate)
        sendEvent(name: "level", value: level as Integer)
        if ((level as Integer) > 0) sendEvent(name: "switch", value: "on")
    }
    sendEvent(name: "colorTemperature", value: k, unit: "K")
    return cmds
}

def refresh() {
    if (debugLogging) log.debug "refresh()"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    return zigbee.onOffRefresh() +
           zigbee.levelRefresh() +
           zigbee.readAttribute(0x0006, 0x4003) +   // StartUpOnOff (Power-On Behavior)
           zigbee.readAttribute(0x0300, 0x0007)     // ColorTemperatureMireds
}

def configure() {
    if (debugLogging) log.debug "configure() - setting reporting and bindings"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    def cmds = zigbee.onOffConfig() +
               zigbee.levelConfig()
    // Configure reporting for Color Temperature (mireds)
    cmds += zigbee.configureReporting(0x0300, 0x0007, 0x21, 1, 3600, 10)
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
    schedule("0 */2 * ? * *", "doHealthCheck") // every 2 minutes (unchanged)
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
