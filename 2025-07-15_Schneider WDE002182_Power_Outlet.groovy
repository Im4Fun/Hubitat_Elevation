/**
 *  Schneider WDE002182 Power Outlet Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Version: 1.0.0
 *  Date: 2025-07-15
 *
 *  Description:
 *   - Supports power, voltage, and ampere monitoring
 *   - Optional logging features
 *
 *  Changelog:
 *   1.0.0 - Initial public release. Cleaned up and production-ready.
 */

metadata {
    definition(name: "Schneider WDE002182 Power Outlet", namespace: "calle", author: "Carl Rådetorp") {
        capability "Actuator"
        capability "Switch"
        capability "PowerMeter"
        capability "VoltageMeasurement"
        capability "Refresh"
        capability "Configuration"
        capability "Sensor"
        capability "Health Check"

        attribute "current", "number" // Custom attribute for amps

        fingerprint profileId: "0104", 
                    endpointId: "06", 
                    inClusters: "0000,0003,0004,0005,0006,0702,0708,0B04,0B05,FC04", 
                    outClusters: "0019", 
                    model: "SOCKET/OUTLET/2", 
                    manufacturer: "Schneider Electric", 
                    controllerType: "ZGB"
    }

    preferences {
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    logDebug "Installed"
    initialize()
}

def updated() {
    logDebug "Updated preferences"
    if (debugLogging) {
        log.warn "Debug logging will be automatically disabled in 30 minutes."
        runIn(1800, "disableDebugLogging")
    }
    initialize()
}

// Legacy alias (optional, for safety)
def logsOff() {
    disableDebugLogging()
}

def initialize() {
    sendEvent(name: "checkInterval", value: 2 * 60 * 60, unit: "seconds") // 2 hours
    configure()
}

def disableDebugLogging() {
    log.warn "Debug logging disabled automatically."
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

def configure() {
    logDebug "Configuring device..."

    sendEvent(name: "switch", value: "off")

    def cmds = []

    cmds += zigbee.onOffConfig()
    cmds += zigbee.configureReporting(0x0702, 0x0400, 0x25, 10, 600, 1)  // Power (W)
    cmds += zigbee.configureReporting(0x0B04, 0x0505, 0x21, 10, 600, 1)  // Voltage (V)
    cmds += zigbee.configureReporting(0x0B04, 0x0508, 0x21, 10, 600, 1)  // Current (A)

    return delayBetween(cmds, 200)
}

def parse(String description) {
    logDebug "Parsing: ${description}"
    def event = zigbee.getEvent(description)
    if (event) {
        logDebug "Zigbee event: ${event}"
        sendEvent(event)
    } else {
        def descMap = zigbee.parseDescriptionAsMap(description)
        logDebug "DescMap: ${descMap}"

        switch (descMap?.clusterInt) {
            case 0x0702: // Power
                if (descMap.attrInt == 0x0400) {
                    def rawPower = Integer.parseInt(descMap.value, 16)
                    def power = rawPower / 1000.0 // milliwatts → watts
                    sendEvent(name: "power", value: String.format("%.2f", power as BigDecimal), unit: "W")
                }
                break

            case 0x0B04: // Electrical Measurement
                if (descMap.attrInt == 0x0505) { // Voltage
                    def rawVolts = Integer.parseInt(descMap.value, 16)
                    def volts = rawVolts / 1.0 // Already in volts
                    sendEvent(name: "voltage", value: volts, unit: "V")
                } else if (descMap.attrInt == 0x0508) { // Current
                    def rawAmps = Integer.parseInt(descMap.value, 16)
                    def amps = rawAmps / 1000.0 // milliamps → amps
                    sendEvent(name: "current", value: String.format("%.2f", amps as BigDecimal), unit: "A")
                }
                break
        }
    }
}

def on() {
    logDebug "Sending ON command"
    zigbee.on()
}

def off() {
    logDebug "Sending OFF command"
    zigbee.off()
}

def refresh() {
    logDebug "Refreshing attributes..."
    return delayBetween([
        zigbee.readAttribute(0x0006, 0x0000), // On/Off
        zigbee.readAttribute(0x0702, 0x0400), // Power
        zigbee.readAttribute(0x0B04, 0x0505), // Voltage
        zigbee.readAttribute(0x0B04, 0x0508)  // Current
    ], 200)
}

def ping() {
    logDebug "Ping received; calling refresh()"
    refresh()
}

// Required for some apps using Health Check
def healthCheck() {
    logDebug "healthCheck() called"
    ping()
}

// Debug logger
private logDebug(msg) {
    if (debugLogging) log.debug msg
}
