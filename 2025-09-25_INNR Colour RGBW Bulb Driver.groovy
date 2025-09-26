/**
 * INNR Candle Colour Zigbee RGBW Bulb Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Version: 1.0.1
 *  Date: 2025-09-25
 *
 *  Description:
 *  Native Hubitat Zigbee driver for INNR RB 250 C, RB 251 C and RB 286 C E14 candle colour RGBW bulbs.
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
 *  1.0.1 - Added fingerprint for RB286C
 */

metadata {
    definition(name: "INNR Colour RGBW Bulb", namespace: "calle", author: "Carl Rådetorp") {
        capability "Actuator"
        capability "Sensor"               // for Dashboard Attribute tiles
        capability "Switch"
        capability "Switch Level"
        capability "Color Control"
        capability "Color Temperature"
        capability "Change Level"
        capability "Refresh"
        capability "Configuration"
        capability "Health Check"

        attribute "colorMode", "string"
        attribute "supportedColorModes", "JSON_OBJECT"
        attribute "Status", "string"
        attribute "level", "number" // explicitly declare for Scene UI discovery
        attribute "powerOnBehavior", "string" // text: Off/On/Last State/Bulb Default (device dependent)
        attribute "powerOnBehaviorCode", "number" // numeric: 0/1/2/255

        command "configure"
        command "ping"
        command "setPowerOnBehavior"
        command "refreshPowerOnBehavior"

        fingerprint profileId: "0104",
                    inClusters: "0000,0003,0004,0005,0006,0008,0300",
                    outClusters: "0019,000A",
                    manufacturer: "INNR",
                    model: "RB250C"
        fingerprint profileId: "0104",
                    inClusters: "0000,0003,0004,0005,0006,0008,0300",
                    outClusters: "0019,000A",
                    manufacturer: "INNR",
                    model: "RB251C"
        // Fingerprint for INNR RB286C
        fingerprint profileId: "0104",
                    endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,0008,0300,1000,FC57,FC82",
                    outClusters: "0019",
                    manufacturer: "INNR",
                    model: "RB286C",
                    controllerType: "ZGB"
    }

    preferences {
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "transitionTime", type: "number", title: "Transition Time (seconds)", defaultValue: 1, range: "0..10"
        input name: "offlineTimeout", type: "number", title: "Timeout for offline status (minutes)", defaultValue: 10, range: "1..120"
        input name: "powerOnBehavior", type: "enum", title: "Power-On Behavior",
              options: ["0":"Off", "1":"On", "2":"Toggle", "255":"Last State"],
              defaultValue: "255"
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

// NEW: force-advertise attributes so Scene/Room Lighting apps list them immediately
private void advertiseAttributesForScenes() {
    def curSwitch = device.currentValue("switch") ?: "off"
    Integer curLevel = (device.currentValue("level") != null) ? (device.currentValue("level") as Integer) : 0
    def curMode = device.currentValue("colorMode") ?: "RGB"

    sendEvent(name: "switch", value: curSwitch, isStateChange: true, descriptionText: "Advertise switch for Scenes")
    sendEvent(name: "level", value: (curLevel as Integer), isStateChange: true, descriptionText: "Advertise level for Scenes")
    sendEvent(name: "colorMode", value: curMode, isStateChange: true, descriptionText: "Advertise colorMode for Scenes")
    sendEvent(name: "supportedColorModes", value: ["RGB","CT"], isStateChange: true, descriptionText: "Advertise supportedColorModes for Scenes")
}

// ---- Lifecycle ----

def installed() {
    log.debug "Installed"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "level", value: (0 as Integer))
    sendEvent(name: "colorMode", value: "RGB")
    sendEvent(name: "supportedColorModes", value: ["RGB","CT"])
    sendEvent(name: "Status", value: "Online")
    state.lastLevel = 100
    state.lastCheckin = now()
    advertiseAttributesForScenes()   // ensure Scenes sees level immediately
    configure()
    scheduleHealthCheck()
}

def updated() {
    log.debug "Updated"
    sendEvent(name: "switch", value: device.currentValue("switch") ?: "off")
    sendEvent(name: "level", value: (device.currentValue("level") ?: 0) as Integer)
    sendEvent(name: "colorMode", value: device.currentValue("colorMode") ?: "RGB")
    sendEvent(name: "supportedColorModes", value: ["RGB","CT"])
    sendEvent(name: "Status", value: device.currentValue("Status") ?: "Online")
    if (debugLogging) runIn(1800, "logsOff")
    advertiseAttributesForScenes()   // ensure Scenes sees level immediately
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
        if (evt.name in ["hue", "saturation"]) {
            sendEvent(name: "colorMode", value: "RGB")
        }
        if (evt.name == "colorTemperature") {
            sendEvent(name: "colorMode", value: "CT")
        }
        if (evt.name == "level") {
            def evVal = (evt.value instanceof Number) ? (evt.value as Integer) : (evt.value?.toString()?.isNumber() ? evt.value.toInteger() : null)
            if (evVal != null && evVal != 0) state.lastLevel = evVal
            if (evVal == 0) sendEvent(name: "switch", value: "off")
            else if (evVal != null) sendEvent(name: "switch", value: "on")
        }
        if (evt.name == "switch") {
            if (evt.value == "off") sendEvent(name: "level", value: (0 as Integer))
        }
        sendEvent(name: "Status", value: "Online")
        state.lastCheckin = now()
    } else {
        if (debugLogging) log.debug "Unhandled: $description"
    }

    // Parse raw read attributes for StartUpOnOff & Color Temperature (mireds)
    def descMap = zigbee.parseDescriptionAsMap(description)

    // On/Off cluster StartUpOnOff (0x0006 / attr 0x4003)
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

    // Color Control cluster (0x0300) -> Color Temperature attribute 0x0007 (mireds)
    if (descMap?.cluster in ["0300", "300"]) {
        def attr = descMap.attrId ?: descMap.attrInt
        if (attr && "0007".equalsIgnoreCase(attr.toString())) {
            def raw = descMap.value
            if (!raw) return
            Integer mired = null
            try {
                mired = Integer.parseInt(raw, 16)
            } catch (ignored) {}
            // Guard invalid/zero mireds to avoid divide-by-zero
            if (mired == null || mired <= 0) {
                if (debugLogging) log.warn "Ignoring CT report with mireds=${mired ?: 'null'} (avoiding divide by zero)"
                return
            }
            // Compute Kelvin from mireds; clamp to a sane range
            Integer kelvin = Math.max(1000, Math.min(65000, Math.round(1000000.0 / mired)))
            sendEvent(name: "colorTemperature", value: kelvin, descriptionText: "Color Temperature set to ${kelvin} K")
            sendEvent(name: "colorMode", value: "CT")
            if (debugLogging) log.debug "Parsed CT ${mired} mireds -> ${kelvin}K"
        }
    }
}

// ---- Commands ----

def on() {
    if (debugLogging) log.debug "on()"
    def curLevel = device.currentValue("level") as Integer
    def useLevel = (state.lastLevel && state.lastLevel != 0) ? (state.lastLevel as Integer) : 100
    sendEvent(name: "switch", value: "on")
    if (!curLevel || curLevel == 0) {
        sendEvent(name: "level", value: (useLevel as Integer))
        zigbee.setLevel(useLevel as Integer)
    } else {
        zigbee.on()
    }
}

def off() {
    if (debugLogging) log.debug "off()"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "level", value: (0 as Integer))
    zigbee.off()
}


def setLevel(level, duration = null) {
    def time = (duration != null) ? duration : transitionTime
    def safeTime = (time instanceof Number) ? time : (time?.isNumber() ? time.toBigDecimal() : 1)
    def rate = Math.round(safeTime * 10)
    if (debugLogging) log.debug "setLevel(${level}) with transitionTime: ${rate} deciseconds"
    Integer lvl = (level as Integer)
    sendEvent(name: "level", value: lvl)
    if (lvl == 0) {
        sendEvent(name: "switch", value: "off")
    } else {
        sendEvent(name: "switch", value: "on")
        state.lastLevel = lvl
    }
    sendEvent(name: "colorMode", value: "RGB")
    zigbee.setLevel(lvl, rate)
}

// IMPORTANT: Signature matches dimmable driver so Scene UI shows Level with CT

def setColorTemperature(kelvin, level = null, duration = null) {
    if (debugLogging) log.debug "setColorTemperature(${kelvin}, level=${level}, duration=${duration})"
    Integer k = (kelvin as Integer)
    if (!k || k <= 0) return

    // Build transition time (seconds -> deciseconds)
    def baseTime = (duration != null) ? duration : transitionTime
    BigDecimal bdTime = (baseTime instanceof Number) ? baseTime as BigDecimal : (baseTime?.toString()?.isNumber() ? baseTime.toBigDecimal() : 1G)
    if (bdTime < 0G) bdTime = 0G
    Integer rate = Math.round(bdTime * 10G) as Integer

    sendEvent(name: "colorMode", value: "CT")
    sendEvent(name: "switch", value: "on")

    // Kelvin -> mireds (uint16), clamp; prevent 0 to avoid division by zero elsewhere
    Integer mired = Math.max(1, Math.round(1000000.0 / k) as Integer)
    String mHex = String.format("%04X", mired)
    String rHex = String.format("%04X", rate)
    String mLE  = mHex.substring(2,4) + " " + mHex.substring(0,2)
    String rLE  = rHex.substring(2,4) + " " + rHex.substring(0,2)

    def cmds = []

    if (device.currentValue("switch") != "on") {
        cmds += zigbee.on()
        cmds += ["delay 150"]
    }

    // 1) Send moveToColorTemperature first
    cmds += ["he cmd 0x${device.deviceNetworkId} 0x01 0x0300 0x0A {" + mLE + " " + rLE + "}", "delay 150"]

    // 2) Optionally set level with same transition
    if (level != null && ("" + level).isNumber()) {
        Integer lvl = Math.max(0, Math.min(100, (level as Integer)))
        sendEvent(name: "level", value: lvl)
        cmds += zigbee.setLevel(lvl, rate)
        cmds += ["delay 150"]
        cmds += zigbee.levelRefresh()
    }

    // Read back ColorTemperature
    cmds += zigbee.readAttribute(0x0300, 0x0007)
    return cmds
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
        Integer lvl = (color.level as Integer)
        sendEvent(name: "level", value: lvl)
        if (lvl != 0) state.lastLevel = lvl
        cmds += zigbee.setLevel(lvl, rate)
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


def startLevelChange(direction) {
    if (debugLogging) log.debug "startLevelChange(${direction})"
    def dir = ("down".equalsIgnoreCase("${direction}")) ? 0x01 : 0x00 // 0=up, 1=down
    def rate = 0x1F
    sendEvent(name: "switch", value: "on")
    sendEvent(name: "colorMode", value: "RGB")
    String payload = String.format("%02X %02X", dir, rate)
    return zigbee.command(0x0008, 0x05, payload)
}

def stopLevelChange() {
    if (debugLogging) log.debug "stopLevelChange()"
    return zigbee.command(0x0008, 0x07)
}


def refresh() {
    if (debugLogging) log.debug "refresh()"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    advertiseAttributesForScenes()   // ensure Scenes sees level immediately
    return zigbee.onOffRefresh() +
           zigbee.levelRefresh() +
           zigbee.colorTemperatureRefresh() +
           zigbee.readAttribute(0x0300, 0x00) + // Current Hue
           zigbee.readAttribute(0x0300, 0x01) + // Current Saturation
           zigbee.readAttribute(0x0006, 0x4003)   // StartUpOnOff (Power-On Behavior)
}


def configure() {
    if (debugLogging) log.debug "configure() - setting reporting and bindings"
    sendEvent(name: "Status", value: "Online")
    state.lastCheckin = now()
    advertiseAttributesForScenes()   // ensure Scenes sees level immediately
    def cmds = zigbee.onOffConfig() +
               zigbee.levelConfig() +
               zigbee.colorTemperatureConfig() +
               configureColorReporting()
    cmds += setPowerOnBehavior()
    return cmds
}


def configureColorReporting() {
    if (debugLogging) log.debug "Configuring Hue and Saturation reporting"
    def cmds = []
    cmds += zigbee.configureReporting(0x0300, 0x00, 0x20, 1, 3600, 5) // Current Hue
    cmds += zigbee.configureReporting(0x0300, 0x01, 0x20, 1, 3600, 5) // Current Saturation
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
    return zigbee.readAttribute(0x0006, 0x00)
}

// Some apps/platforms call healthCheck() when "Health Check" capability is present.
// Provide it and delegate to ping() to avoid MissingMethodException.

def healthCheck() {
    if (debugLogging) log.debug "healthCheck() -> ping()"
    return ping()
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
