/**
 * Energy Summary Device (Driver)
 *
 *  Author: Carl Rådetorp
 *  Date: 2025-08-12
 *
 *  Description:
 *  Virtual device used by the app to expose totals for dashboards and Rule Machine.
 * 
 * Changelog:
 * 1.0.0 - Initial release ready for production
 */

metadata {
  definition (name: "Energy Summary Device", namespace: "calle", author: "Carl Rådetorp") {
    capability "Sensor"
    attribute "todayEnergy", "NUMBER"
    attribute "monthEnergy", "NUMBER"
    attribute "price", "NUMBER"
    attribute "todayCost", "NUMBER"
    attribute "monthCost", "NUMBER"
    attribute "lastUpdated", "STRING"
    command "resetAttributes"
    command "configure"
  }
  preferences {
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
  }
}

def installed() {
  if (logEnable) log.debug "installed()"
  configure()
}

def updated() {
  if (logEnable) log.debug "updated()"
  runIn(1800, "logsOff")
}

def logsOff() {
  device.updateSetting("logEnable", [value:"false", type:"bool"])
}

def resetAttributes() {
  sendEvent(name: "todayEnergy", value: 0, unit: "kWh")
  sendEvent(name: "monthEnergy", value: 0, unit: "kWh")
  sendEvent(name: "price", value: 0)
  sendEvent(name: "todayCost", value: 0)
  sendEvent(name: "monthCost", value: 0)
  sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getTimeZone("UTC")))
}

def configure() {
  if (logEnable) log.debug "configure() invoked"
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
  try { parent?.updateChild() } catch (e) { if (logEnable) log.debug "No parent update available: ${e}" }
}
