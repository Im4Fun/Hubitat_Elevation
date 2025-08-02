/**
 * Innr Candle Colour Zigbee RGBW Bulb Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Namespace: calle
 *  Version: 1.0.0
 *  Date: 2025-08-02
 *
 *  Description:
 *  Native Hubitat Zigbee driver for Innr RB 250 C and RB 251 C E14 candle colour RGBW bulbs.
 *  Scene and Room Lighting compatible: setColor always turns bulb on (matches built-in driver logic).
 *  All attributes always initialized and kept in sync.
 *  Remembers last non-zero level for on().
 *  Supports on/off, brightness, color temperature, and color control (Hue/Saturation).
 *  Configures standard reporting and adds manual hue/saturation reporting.
 *  User-configurable transition time for smooth fades.
 *  setHue(), setSaturation(), colorMode tracking, basic health check, and full attribute sync.
 *
 *  Changelog:
 *  1.0.0 - Initial release, production-ready and fully Room Lighting/Scene compatible
 */

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
    }
}

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
    sendEvent(name: "colorMode", value: "RGB")
    zigbee.setLevel(level, rate)
}

def setColorTemperature(temp) {
    def time = transitionTime
    def safeTime = (time instanceof Number) ? time : (time?.isNumber() ? time.toBigDecimal() : 1)
    def rate = Math.round(safeTime * 10)
    if (debugLogging) log.debug "setColorTemperature(${temp}) with transitionTime: ${rate} deciseconds"
    sendEvent(name: "colorMode", value: "CT")
    sendEvent(name: "switch", value: "on")
    zigbee.setColorTemperature(temp, rate)
}

def setColor(Map color) {
    def time = transitionTime
    def safeTime = (time instanceof Number) ? time : (time?.isNumber() ? time.toBigDecimal() : 1)
    def rate = Math.round(safeTime * 10)
    if (debugLogging) log.debug "setColor(${color}) with transitionTime: ${rate} deciseconds"
    sendEvent(name: "colorMode", value: "RGB")
    sendEvent(name: "switch", value: "on")
    def cmds = []
    if (color.level != null) {
        sendEvent(name: "level", value: color.level)
        if (color.level != 0) state.lastLevel = color.level
        cmds += zigbee.setLevel(color.level, rate)
    } else {
        cmds += zigbee.on()
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
    setColor(color)
}

def setSaturation(sat) {
    if (debugLogging) log.debug "setSaturation(${sat})"
    def color = [
        hue: device.currentValue("hue") ?: 0,
        saturation: sat
    ]
    setColor(color)
}

def refresh() {
    if (debugLogging) log.debug "refresh()"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    return zigbee.onOffRefresh() +
           zigbee.levelRefresh() +
           zigbee.colorTemperatureRefresh() +
           zigbee.readAttribute(0x0300, 0x00) + // Current Hue
           zigbee.readAttribute(0x0300, 0x01)   // Current Saturation
}

def configure() {
    if (debugLogging) log.debug "configure() - setting reporting and bindings"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    return zigbee.onOffConfig() +
           zigbee.levelConfig() +
           zigbee.colorTemperatureConfig() +
           configureColorReporting()
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
    zigbee.readAttribute(0x0006, 0x00)
}

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
