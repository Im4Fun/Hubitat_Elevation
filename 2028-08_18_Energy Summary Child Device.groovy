/**
 *  Energy Summary Device
 *
 *  Child device used by the Estimated Energy (Bulbs & Switches) app
 *  and Energy Cost agregator app to expose totals and current values.
 *
 *  Author: Carl Rådetorp
 *  Version: 1.5.0
 *  Date: 2025-08-18
 *
 *  Capabilities:
 *    - PowerMeter  (attribute: power, W)
 *    - EnergyMeter (attribute: energy, kWh)
 *
 *  Custom attributes:
 *    - todayEnergy   (kWh, reset daily)
 *    - monthEnergy   (kWh, reset monthly)
 *    - price         (current price per kWh incl. fees)
 *    - todayCost     (currency, today’s cost)
 *    - monthCost     (currency, this month’s cost)
 *    - lastUpdated   (STRING, timestamp)
 *    - summaryHtml   (STRING, pre-formatted summary for dashboards)
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
        attribute "summaryHtml", "STRING"
    }
}

def installed() {
    log.info "${device.displayName} installed"
}

def updated() {
    log.info "${device.displayName} updated"
}

// Dashboard / RM can call Refresh → ask parent app to push new values
def refresh() {
    log.debug "Refresh called on ${device.displayName}"
    if (parent) {
        parent.updateChild()
    }
}

/**
 * Catch events sent from the parent app and rebuild summaryHtml
 */
def parse(String description) {
    // Not used, but required by Hubitat
}

def sendEvent(Map evt) {
    // Intercept events from parent
    super.sendEvent(evt)
    if (["todayEnergy","monthEnergy","price","todayCost","monthCost","power","lastUpdated"].contains(evt.name)) {
        runInMillis(100, "rebuildSummary")
    }
}

def rebuildSummary() {
    def todayEnergy = device.currentValue("todayEnergy") ?: "-"
    def todayCost   = device.currentValue("todayCost")   ?: "-"
    def monthEnergy = device.currentValue("monthEnergy") ?: "-"
    def monthCost   = device.currentValue("monthCost")   ?: "-"
    def price       = device.currentValue("price")       ?: "-"
    def power       = device.currentValue("power")       ?: "-"
    def lastUpdated = device.currentValue("lastUpdated") ?: "-"

    def summary = """<div style='font-family:sans-serif;'>
<b>Today:</b> ${todayEnergy} kWh / ${todayCost}<br>
<b>Month:</b> ${monthEnergy} kWh / ${monthCost}<br>
<b>Now:</b> ${power} W<br>
<b>Price:</b> ${price} /kWh<br>
<i>Updated:</i> ${lastUpdated}
</div>"""

    super.sendEvent(name: "summaryHtml", value: summary)
}
