/**
 * Aeotec Home Energy Meter 8 (ZWA046) Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Version: 1.0.0
 *  Date: 2025-08-02
 *
 *  Description:
 *  Supports EU/US/AU Z-Wave 800-series models. Includes multi-clamp endpoint metering,
 *  long-range and SmartStart support, diagnostic event support, and detailed meter reporting:
 *  kWh, W, V, A, kVAh, PF, kVar, kVarh.
 */

metadata {
    definition(name: "Aeotec Home Energy Meter 8 (ZWA046)", namespace: "calle", author: "Carl Rådetorp") {
        capability "Power Meter"
        capability "Energy Meter"
        capability "Voltage Measurement"
        capability "Refresh"
        capability "Configuration"

        command "getConfig"
        command "resetMeter"
        command "endpoint0Refresh"

        attribute "current", "number"
        attribute "logLevel", "string"

        fingerprint mfr: "0371", prod: "0000", deviceId: "0033"
        fingerprint mfr: "0371", prod: "0100", deviceId: "0033"
        fingerprint mfr: "0371", prod: "0200", deviceId: "0033"
    }

    preferences {
        input name: "group1ReportValues", type: "number", title: "Group 1 Reports Consumption", defaultValue: 50529024
        input name: "group2ReportValues", type: "number", title: "Group 2 Reports Consumption", defaultValue: 202116096
        input name: "group3ReportValues", type: "number", title: "Group 3 Reports Consumption", defaultValue: 4042321920
        input name: "group4ReportValues", type: "number", title: "Group 4 Reports Production", defaultValue: 50529024
        input name: "group5ReportValues", type: "number", title: "Group 5 Reports Production", defaultValue: 202116096
        input name: "group6ReportValues", type: "number", title: "Group 6 Reports Production", defaultValue: 4042321920
        input name: "group1ReportInterval", type: "number", title: "Group 1 Report Interval (s)", defaultValue: 3600
        input name: "group2ReportInterval", type: "number", title: "Group 2 Report Interval (s)", defaultValue: 7200
        input name: "group3ReportInterval", type: "number", title: "Group 3 Report Interval (s)", defaultValue: 7200
        input name: "group4ReportInterval", type: "number", title: "Group 4 Report Interval (s)", defaultValue: 3600
        input name: "group5ReportInterval", type: "number", title: "Group 5 Report Interval (s)", defaultValue: 7200
        input name: "group6ReportInterval", type: "number", title: "Group 6 Report Interval (s)", defaultValue: 7200
        input name: "logDisableMinutes", type: "number", title: "Disable logging after (minutes)", defaultValue: 30
        input name: "logLevel", type: "enum", title: "Logging Level", options: ["None", "Error", "Warn", "Info", "Debug"], defaultValue: "Info"
        input name: "reportInterval", type: "number", title: "Reporting Interval (seconds)", defaultValue: 30
        input name: "clampCount", type: "enum", title: "Number of Clamps", options: ["1", "2", "3"], defaultValue: "1"
    }
}

// Install & Update

def installed() {
    initialize()
    if (logLevel != "None") runIn(logDisableMinutes * 60, logsOff)
}

def updated() {
    initialize()
    if (logLevel != "None") runIn(logDisableMinutes * 60, logsOff)
    sendEvent(name: "logLevel", value: settings.logLevel ?: "Info")
}

def logsOff() {
    device.updateSetting("logLevel", [value: "None", type: "enum"])
    log.info "Logging disabled automatically"
}

def initialize() {
    def clamps = (settings?.clampCount instanceof String && settings.clampCount.isInteger()) ? settings.clampCount.toInteger() : 1
    sendEvent(name: "numberOfEndpoints", value: clamps)
    configure()
}

// Continuation of driver functionality

def configure() {
    def cmds = []
    cmds += [
        zwave.configurationV1.configurationSet(parameterNumber: 101, size: 4, scaledConfigurationValue: (settings?.group1ReportValues ?: 50529024).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 102, size: 4, scaledConfigurationValue: (settings?.group2ReportValues ?: 202116096).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 103, size: 4, scaledConfigurationValue: (settings?.group3ReportValues ?: 4042321920).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 104, size: 4, scaledConfigurationValue: (settings?.group4ReportValues ?: 50529024).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 105, size: 4, scaledConfigurationValue: (settings?.group5ReportValues ?: 202116096).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 106, size: 4, scaledConfigurationValue: (settings?.group6ReportValues ?: 4042321920).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 111, size: 4, scaledConfigurationValue: (settings?.group1ReportInterval ?: 3600).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 112, size: 4, scaledConfigurationValue: (settings?.group2ReportInterval ?: 7200).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 113, size: 4, scaledConfigurationValue: (settings?.group3ReportInterval ?: 7200).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 114, size: 4, scaledConfigurationValue: (settings?.group4ReportInterval ?: 3600).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 115, size: 4, scaledConfigurationValue: (settings?.group5ReportInterval ?: 7200).toInteger()).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 116, size: 4, scaledConfigurationValue: (settings?.group6ReportInterval ?: 7200).toInteger()).format()
    ]
    return delayBetween(cmds, 500)
}

def refresh() {
    if (!state?.zwaveInfo) {
        log.warn "Device not initialized with zwaveInfo; skipping refresh."
        return
    }
    def cmds = []
    def count = (settings?.clampCount instanceof String && settings.clampCount.isInteger()) ? settings.clampCount.toInteger() : 1
    (1..count).each { ep ->
        def mcClass = zwave?.multiChannelV4
        if (mcClass && ep > 0 && ep <= 3) {
            def mcCmd = mcClass.multiChannelCmdEncap(
                sourceEndPoint: 0,
                destinationEndPoint: ep,
                commandClass: 0x32,
                command: 0x01
            )
            if (mcCmd?.hasProperty("format")) {
                try {
                    def result = mcCmd.format()
                    if (result) cmds << result
                } catch (Exception e) {
                    log.warn "Failed to format MC command for endpoint ${ep}: ${e.message}"
                }
            } else {
                log.warn "multiChannelCmdEncap returned null or unformattable for endpoint ${ep}"
            }
        } else {
            log.warn "Invalid endpoint: ${ep} (Clamp count is ${count})"
        }
    }
    return delayBetween(cmds, 300)
}

def getConfig() {
    def cmds = []
    (101..106).each {
        cmds << zwave.configurationV1.configurationGet(parameterNumber: it).format()
    }
    (111..116).each {
        cmds << zwave.configurationV1.configurationGet(parameterNumber: it).format()
    }
    return delayBetween(cmds, 500)
}

def resetMeter() {
    def cmds = []
    def count = (settings?.clampCount instanceof String && settings.clampCount.isInteger()) ? settings.clampCount.toInteger() : 1
    (1..count).each { ep ->
        def mcCmd = zwave?.multiChannelV4?.multiChannelCmdEncap(
            destinationEndPoint: ep,
            commandClass: 0x32,
            command: 0x05
        )
        if (mcCmd != null && mcCmd.respondsTo("format")) {
            try {
                def result = mcCmd.format()
                if (result) cmds << result
            } catch (Exception e) {
                log.warn "Failed to format resetMeter command for endpoint ${ep}: ${e.message}"
            }
        } else {
            log.warn "multiChannelCmdEncap returned null or unformattable for resetMeter endpoint ${ep}"
        }
    }
    return delayBetween(cmds, 500)
}

def endpoint0Refresh() {
    def cmds = []
    [0, 1, 2, 4, 5, 6, 7, 8].each { scale ->
        def scale2 = (scale == 7) ? 0 : (scale == 8 ? 1 : null)
        if (scale2 != null) {
            cmds << zwave.meterV4.meterGet(scale: 7, scale2: scale2).format()
        } else {
            cmds << zwave.meterV4.meterGet(scale: scale).format()
        }
    }
    return delayBetween(cmds, 300)
}

def parse(String description) {
    try {
        def cmd = zwave.parse(description, getCommandClassVersions())
        if (cmd) return zwaveEvent(cmd)
        if ((settings.logLevel ?: "Info") in ["Warn", "Info", "Debug"]) log.warn "Unparsed: $description"
    } catch (Exception e) {
        log.error "Exception in parse: ${e.message}"
    }
}

def zwaveEvent(hubitat.zwave.commands.meterv4.MeterReport cmd) {
    def unit = ""
    def name = ""
    def value = cmd.scaledMeterValue
    switch (cmd.scale) {
        case 0: name = "energy"; unit = "kWh"; break
        case 1: name = "current"; unit = "A"; break
        case 2: name = "power"; unit = "W"; break
        case 4: name = "kVAh"; unit = "kVAh"; break
        case 5: name = "voltage"; unit = "V"; break
        case 6: name = "powerFactor"; unit = ""; break
        case 7: name = "reactivePower"; unit = "kVar"; break
        case 8: name = "reactiveEnergy"; unit = "kVarh"; break
        default:
            if ((settings.logLevel ?: "Info") == "Debug") log.debug "Unhandled MeterReport scale=${cmd.scale}"
            return
    }
    def endpoint = cmd.sourceEndPoint ?: 0
    def eventName = (endpoint > 0) ? "${name}_${endpoint}" : name
    sendEvent(name: eventName, value: value, unit: unit)
    if (endpoint > 0) {
        def friendlyName = "${name}${endpoint}".replaceAll(/[^a-zA-Z0-9]/, "")
        sendEvent(name: friendlyName, value: value, unit: unit)
    }
}

def zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    try {
        def embeddedCmd = cmd.encapsulatedCommand(getCommandClassVersions())
        if (embeddedCmd) {
            embeddedCmd.sourceEndPoint = cmd.sourceEndPoint
            return zwaveEvent(embeddedCmd)
        }
    } catch (Exception e) {
        if ((settings.logLevel ?: "Info") in ["Error", "Warn", "Info", "Debug"]) log.error "Exception in MultiChannelCmdEncap: ${e.message}"
    }
}



def zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
    log.info "Supervision Command Received: ${cmd.commandClass} cmd=${cmd.command}"
    return zwave.supervisionV1.supervisionReport(
        sessionID: cmd.sessionID,
        moreStatusUpdates: false,
        status: 0xFF
    ).format()
}

def zwaveEvent(hubitat.zwave.Command cmd) {
    try {
        if ((settings.logLevel ?: "Info") == "Debug") log.debug "Unhandled Z-Wave event: ${cmd}"
    } catch (Exception e) {
        log.error "Exception in generic Z-Wave handler: ${e.message}"
    }
}

private getCommandClassVersions() {
    [
        0x32: 4, // Meter
        0x60: 3, // MultiChannel
        0x5E: 2, // Z-Wave Plus Info
        0x6C: 1, // Supervision
        0x70: 2, // Configuration
        0x86: 1, // Version
        0x72: 2  // Manufacturer Specific
    ]
}
