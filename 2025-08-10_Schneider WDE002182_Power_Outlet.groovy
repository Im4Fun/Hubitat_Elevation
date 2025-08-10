/**
 *  Schneider WDE002182 Power Outlet Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Version: 1.2.1
 *  Date: 2025-08-10
 *
 *  Adds:
 *   - EnergyMeter capability (kWh accumulation from power W)
 *   - User-configurable "price per kWh" and currency
 *   - Custom command setEnergyPrice() to update spot/variable price at runtime
 *   - Live cost tracking (energy * price)
 *   - Automatic monthly reset at 00:00 on the 1st of each month (end-of-month rollover)
 *   - Manual resetEnergy() command
 *   - Safer math (BigDecimal) and guards for missing readings
 *   - Optional info log for resetEnergy
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
        capability "EnergyMeter"

        attribute "current", "number"
        attribute "cost", "number"
        attribute "energyPrice", "number"
        attribute "lastReset", "string"

        command "resetEnergy"
        command "setEnergyPrice", ["number"]
    }

    preferences {
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "infoLogging", type: "bool", title: "Enable info logging for resets and updates", defaultValue: true
        input name: "pricePerKwh", type: "decimal", title: "Price per kWh (e.g., 1.23)", defaultValue: 0.0
        input name: "currency", type: "enum", title: "Currency", options: ["SEK","EUR","USD","GBP","NOK","DKK","PLN","Other"], defaultValue: "SEK"
        input name: "autoResetMonthly", type: "bool", title: "Auto reset at end of month (00:00 on the 1st)", defaultValue: true
    }
}

// ===== Lifecycle =====

def installed() { logDebug "Installed"; initialize() }

def updated() {
    logDebug "Updated preferences"
    if (debugLogging) {
        log.warn "Debug logging will be automatically disabled in 30 minutes."
        runIn(1800, "disableDebugLogging")
    }
    initialize()
}

def logsOff() { disableDebugLogging() }

private initialize() {
    sendEvent(name: "checkInterval", value: 2 * 60 * 60, unit: "seconds")
    state.lastTs = now()
    state.energyWh = (state.energyWh ?: 0L) as Long
    state.lastPowerW = (device.currentValue("power") ?: 0) as BigDecimal
    if (pricePerKwh != null) sendEvent(name: "energyPrice", value: toBD(pricePerKwh))
    unschedule()
    runEvery1Minute("tick")
    schedule("0 0 0 * * ?", "dailyMidnight")
    configure()
}

private disableDebugLogging() {
    log.warn "Debug logging disabled automatically."
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

// ===== Configure reporting from device =====

def configure() {
    logDebug "Configuring device..."
    def cmds = []
    cmds += zigbee.onOffConfig()
    cmds += zigbee.configureReporting(0x0702, 0x0400, 0x25, 10, 600, 1)
    cmds += zigbee.configureReporting(0x0B04, 0x0505, 0x21, 10, 600, 1)
    cmds += zigbee.configureReporting(0x0B04, 0x0508, 0x21, 10, 600, 1)
    return delayBetween(cmds, 200)
}

// ===== Parsing =====

def parse(String description) {
    logDebug "Parsing: ${description}"
    def event = zigbee.getEvent(description)
    if (event) {
        logDebug "Zigbee event: ${event}"
        sendEvent(event)
        if (event.name == "power") {
            try { state.lastPowerW = toBD(event.value) } catch (e) { state.lastPowerW = toBD(0) }
        }
        return
    }
    def descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "DescMap: ${descMap}"
    switch (descMap?.clusterInt) {
        case 0x0702:
            if (descMap.attrInt == 0x0400) {
                def rawPower = Integer.parseInt(descMap.value, 16)
                def power = toBD(rawPower).divide(new BigDecimal(1000), 3, java.math.RoundingMode.HALF_UP)
                state.lastPowerW = power
                sendEvent(name: "power", value: power.setScale(2, java.math.RoundingMode.HALF_UP), unit: "W")
            }
            break
        case 0x0B04:
            if (descMap.attrInt == 0x0505) {
                def rawVolts = Integer.parseInt(descMap.value, 16)
                def volts = toBD(rawVolts)
                sendEvent(name: "voltage", value: volts, unit: "V")
            } else if (descMap.attrInt == 0x0508) {
                def rawAmps = Integer.parseInt(descMap.value, 16)
                def amps = toBD(rawAmps).divide(new BigDecimal(1000), 3, java.math.RoundingMode.HALF_UP)
                sendEvent(name: "current", value: amps.setScale(2, java.math.RoundingMode.HALF_UP), unit: "A")
            }
            break
    }
}

// ===== Commands =====

def on()  { logDebug "Sending ON";  zigbee.on() }

def off() { logDebug "Sending OFF"; zigbee.off() }

def refresh() {
    logDebug "Refreshing attributes..."
    return delayBetween([
        zigbee.readAttribute(0x0006, 0x0000),
        zigbee.readAttribute(0x0702, 0x0400),
        zigbee.readAttribute(0x0B04, 0x0505),
        zigbee.readAttribute(0x0B04, 0x0508)
    ], 200)
}

def ping() { logDebug "Ping received; calling refresh()"; refresh() }

def healthCheck() { logDebug "healthCheck() called"; ping() }

def resetEnergy() {
    state.energyWh = 0L
    sendEvent(name: "energy", value: 0.0, unit: "kWh")
    sendEvent(name: "cost", value: 0.0, unit: currency)
    sendEvent(name: "lastReset", value: isoNow())
    if (infoLogging) log.info "Energy and cost reset."
}

def setEnergyPrice(value) {
    def p = toBD(value ?: 0)
    sendEvent(name: "energyPrice", value: p)
    computeCost()
    if (infoLogging) log.info "Energy price updated to ${p} ${currency}/kWh"
}

// ===== Scheduling helpers =====

def dailyMidnight() {
    if (!autoResetMonthly) return
    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    def day = new Date().format('d', tz) as Integer
    if (day == 1) {
        if (infoLogging) log.info "Monthly auto-reset triggered"
        resetEnergy()
    }
}

def tick() {
    try {
        def nowMs = now()
        def dtMs = nowMs - (state.lastTs ?: nowMs)
        state.lastTs = nowMs
        if (dtMs <= 0) return
        def pW = (state.lastPowerW ?: toBD(device.currentValue("power") ?: 0)) as BigDecimal
        if (pW < 0) pW = toBD(0)
        BigDecimal wh = pW.multiply(new BigDecimal(dtMs)).divide(new BigDecimal(3600000), 6, java.math.RoundingMode.HALF_UP)
        long whLong = wh.setScale(0, java.math.RoundingMode.HALF_UP).longValue()
        state.energyWh = (state.energyWh ?: 0L) + whLong
        BigDecimal kwh = new BigDecimal(state.energyWh).divide(new BigDecimal(1000), 3, java.math.RoundingMode.HALF_UP)
        sendEvent(name: "energy", value: kwh, unit: "kWh")
        computeCost(kwh)
    } catch (e) {
        log.warn "tick() error: ${e.message}"
    }
}

private computeCost(BigDecimal kwh = null) {
    try {
        if (kwh == null) kwh = new BigDecimal(state.energyWh ?: 0).divide(new BigDecimal(1000), 3, java.math.RoundingMode.HALF_UP)
        def price = toBD(device.currentValue("energyPrice") ?: pricePerKwh ?: 0)
        BigDecimal cost = kwh.multiply(price)
        sendEvent(name: "cost", value: cost.setScale(2, java.math.RoundingMode.HALF_UP), unit: currency)
    } catch (e) {
        log.warn "computeCost() error: ${e.message}"
    }
}

// ===== Logging Helpers =====
private void logDebug(Object msg) {
    try {
        if (debugLogging) log.debug "${device.displayName ?: device.name}: ${msg}"
    } catch (e) {
        if (debugLogging) log.debug "${msg}"
    }
}

// ===== Utils =====
private BigDecimal toBD(val) {
    if (val == null) return new BigDecimal(0)
    if (val instanceof BigDecimal) return val
    try { return new BigDecimal("" + val) } catch (e) { return new BigDecimal(0) }
}

private String isoNow() {
    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    return new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", tz)
}
