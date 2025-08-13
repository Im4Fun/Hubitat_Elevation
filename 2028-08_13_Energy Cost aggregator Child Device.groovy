/**
 * Energy Summary Device (Driver)
 *
 * Author: Carl Rådetorp
 * Version: 1.0.1
 * Date: 2025-08-13
 *
 * Description:
 *  Virtual device used by the app to expose totals for Rule Machine and Dashboards.
 *
 * Changelog:
 * 1.0.1 - Add Refresh capability and syncFromParent; tidy defaults & logging
 * 1.0.0 - Initial release ready for production
 */

metadata {
  definition (name: "Energy Summary Device", namespace: "calle", author: "Carl Rådetorp") {
    capability "Sensor"
    capability "Refresh"
    capability "Configuration"

    attribute "todayEnergy", "NUMBER"
    attribute "monthEnergy", "NUMBER"
    attribute "price", "NUMBER"
    attribute "todayCost", "NUMBER"
    attribute "monthCost", "NUMBER"
    attribute "lastUpdated", "STRING"

    command "resetAttributes"
    command "syncFromParent"
  }
  preferences {
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
  }
}

def installed() {
  if (logEnable) log.debug "installed()"
  runIn(1800, "logsOff")
  configure()
}

def updated() {
  if (logEnable) log.debug "updated()"
  runIn(1800, "logsOff")
  // Do not call configure() automatically to avoid overwriting values right after app writes them.
}

def logsOff() {
  device.updateSetting("logEnable", [value:"false", type:"bool"])
  log.info "Debug logging disabled"
}

def configure() {
  if (logEnable) log.debug "configure() invoked"
  // Initialize attributes if missing
  ["todayEnergy","monthEnergy","price","todayCost","monthCost","lastUpdated"].each { attr ->
    if (device.currentValue(attr) == null) {
      switch(attr){
        case 'todayEnergy': sendEvent(name:attr, value:0, unit:'kWh'); break
        case 'monthEnergy': sendEvent(name:attr, value:0, unit:'kWh'); break
        case 'price':       sendEvent(name:attr, value:0); break
        case 'todayCost':   sendEvent(name:attr, value:0); break
        case 'monthCost':   sendEvent(name:attr, value:0); break
        case 'lastUpdated': sendEvent(name:attr, value:new Date().format('yyyy-MM-dd HH:mm:ss', location?.timeZone ?: TimeZone.getTimeZone('UTC'))); break
      }
    }
  }
  // Try to sync current totals from parent app
  try { parent?.updateChild() } catch (e) { if (logEnable) log.debug "No parent update available: ${e}" }
}

def resetAttributes() {
  if (logEnable) log.debug "resetAttributes()"
  sendEvent(name: "todayEnergy", value: 0, unit: "kWh")
  sendEvent(name: "monthEnergy", value: 0, unit: "kWh")
  sendEvent(name: "price", value: 0)
  sendEvent(name: "todayCost", value: 0)
  sendEvent(name: "monthCost", value: 0)
  sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getTimeZone("UTC")))
}

def refresh() {
  if (logEnable) log.debug "refresh() -> ask parent to update"
  try { parent?.updateChild() } catch (e) { if (logEnable) log.debug "No parent to refresh from: ${e}" }
}

def syncFromParent() {
  if (logEnable) log.debug "syncFromParent()"
  refresh()
}
