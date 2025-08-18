/**
 *  Energy Summary Child Device
 *
 *  Child device used by the Estimated Energy (Bulbs & Switches) app
 *  and Energy Cost agrregator app to expose totals and current values.
 *
 *  Author: Carl Rådetorp
 *  Version: 1.3.0
 *  Date: 2025-08-18
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
    definition(name: "Energy Summary Device",
               namespace: "calle",
               author: "Carl Rådetorp") {

        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Sensor"
        capability "Refresh"

        // Custom attributes
        attribute "todayEnergy", "NUMBER"
        attribute "monthEnergy", "NUMBER"
        attribute "price", "NUMBER"
        attribute "todayCost", "NUMBER"
        attribute "monthCost", "NUMBER"
        attribute "lastUpdated", "STRING"
    }
}

def installed() {
    log.info "${device.displayName} installed"
}

def updated() {
    log.info "${device.displayName} updated"
}

// Hubitat dashboard / RM can call Refresh
def refresh() {
    log.debug "Refresh called on ${device.displayName}"
    if (parent) {
        parent.updateChild([refresh:true])
    }
}
