/**
 * Schneider WDE002348 Zigbee Switch Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Namespace: calle
 *  Version: 1.0.0
 *  Date: 2025-08-02
 *
 *  Description:
 *  Native Hubitat Zigbee driver for Schneider Electric NHPB/SWITCH/1 single-gang Zigbee switch.
 *  Provides on/off, refresh, and configuration commands.
 *  Implements standard Zigbee switch capabilities and reporting for reliable operation.
 *  Custom fingerprint for model identification and seamless pairing.
 *  User-configurable debug logging with automatic timeout.
 *  Basic health check included.
 *
 *  Changelog:
 *  1.0.0 - Initial release for Schneider Electric Zigbee switches with standard on/off support
 */

metadata {
    definition (name: "Schneider WDE002348 Zigbee Switch", namespace: "calle", author: "Carl Rådetorp") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Configuration"
        capability "Health Check"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0B05", outClusters: "0019", model: "NHPB/SWITCH/1", manufacturer: "Schneider Electric"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def parse(String description) {
    if (logEnable) log.debug "parse: $description"
    def event = zigbee.getEvent(description)
    if (event) {
        if (logEnable) log.debug "Event: $event"
        sendEvent(event)
    } else {
        if (logEnable) log.debug "Unhandled: $description"
    }
}

def on() {
    if (logEnable) log.debug "Sending ON command"
    zigbee.on()
}

def off() {
    if (logEnable) log.debug "Sending OFF command"
    zigbee.off()
}

def refresh() {
    if (logEnable) log.debug "Refreshing"
    return zigbee.onOffRefresh()
}

def configure() {
    if (logEnable) log.debug "Configuring Reporting and Bindings"
    sendEvent(name: "checkInterval", value: 60 * 60 * 12, unit: "second", displayed: false)
    def cmds = []
    cmds += zigbee.onOffConfig()
    cmds += zigbee.onOffRefresh()
    return cmds
}

def installed() {
    if (logEnable) log.debug "Installed"
    configure()
}

def updated() {
    if (logEnable) log.debug "Updated"
    if (logEnable) runIn(1800, logsOff)
    configure()
}

def logsOff() {
    log.warn "Debug logging disabled."
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}
