/**
 *  Unified Energy (Meters + Estimated) Child Device
 *
 *  Child device used by the Unified Energy (Meters + Estimated) app
 *
 *  Author: Carl Rådetorp
 *  Version: 1.3.0
 *  Date: 2025-09-01
 *
 *  Capabilities:
 *    - PowerMeter  (attribute: power, W)   → current total instantaneous watts
 *    - EnergyMeter (attribute: energy, kWh) → running monthly energy
 *
 *  Custom attributes (NUMBER unless noted):
 *    - todayEnergy   (kWh, reset daily)
 *    - monthEnergy   (kWh, reset monthly)
 *    - price         (current price per kWh incl. extra fees)
 *    - todayCost     (currency, today’s cost)
 *    - monthCost     (currency, this month’s cost)
 *    - lastUpdated   (STRING, timestamp)
 */

metadata {
    definition (name: "Energy Summary Device", namespace: "calle", author: "Carl Rådetorp") {
        capability "Sensor"
        capability "Actuator"     // helps some dashboard views consider it tile-able
        capability "PowerMeter"   // attribute: power (W)
        capability "EnergyMeter"  // attribute: energy (kWh)
        capability "Refresh"
        capability "Initialize"

        attribute "todayEnergy", "number"  // kWh
        attribute "monthEnergy", "number"  // kWh
        attribute "todayCost",  "number"   // currency
        attribute "monthCost",  "number"   // currency
        attribute "price",      "number"   // currency/kWh
        attribute "lastUpdated","string"
        attribute "powerEstimated", "number"
    attribute "powerMeters", "number"
}
    preferences {
        input name: "decimalsKWh", type: "number", title: "kWh decimals (display)", defaultValue: 3, range: "0..6"
        input name: "decimalsCost", type: "number", title: "Cost decimals (display)", defaultValue: 2, range: "0..4"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { seed(); if (logEnable) log.debug "Installed" }
def updated()   { seed(); if (logEnable) log.debug "Updated" }

def refresh()   { if (logEnable) log.debug "Refresh requested; values are push-updated by parent." }
def initialize(){ seed() }

private void seed(){
    // Ensure core attributes exist so Dashboard can bind to them immediately
    if (device.currentValue("power")   == null) sendEvent(name: "power", value: 0, unit: "W")
    if (device.currentValue("energy")  == null) sendEvent(name: "energy", value: 0, unit: "kWh")
    if (device.currentValue("todayEnergy") == null) sendEvent(name: "todayEnergy", value: 0, unit: "kWh")
    if (device.currentValue("monthEnergy") == null) sendEvent(name: "monthEnergy", value: 0, unit: "kWh")
    if (device.currentValue("todayCost")   == null) sendEvent(name: "todayCost", value: 0)
    if (device.currentValue("monthCost")   == null) sendEvent(name: "monthCost", value: 0)
    if (device.currentValue("price")       == null) sendEvent(name: "price", value: null)
    sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getTimeZone("UTC")))
}
