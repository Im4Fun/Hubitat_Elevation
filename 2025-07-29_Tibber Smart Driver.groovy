/**
 * Tibber Smart Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Version: 1.0.4
 *  Date: 2025-07-29
 *
 *  Description:
 *  Polls Tibber for electricity prices and consumption data using the Tibber API.
 *  Tracks and reports current price, price level, next hour prices, consumption, and price metadata.
 *  Mirrors price level to presence and temperature for Easy Dashboard compatibility.
 *  Designed for rule automation and dashboard display with minimal UI clutter.
 *
 *  Changelog:
 *  1.0.0 - Initial release
 *  1.0.1 - Dashboard mapping: presence + temperature = priceLevel
 *  1.0.2 - Timestamp parsing now uses milliseconds (SSSZ)
 *  1.0.3 - Fixed null error in consumption
 *  1.0.4 - Fixed invalid cron schedule for 60+ minute intervals
 */

metadata {
  definition (name: "Tibber Smart Driver", namespace: "calle", author: "Carl Rådetorp") {
    capability "Sensor"
    capability "EnergyMeter"
    capability "Refresh"
    capability "TemperatureMeasurement"
    capability "PowerMeter"
    capability "PresenceSensor"

    attribute "currentPrice", "number"
    attribute "priceLevel", "string"
    attribute "priceCurrency", "string"
    attribute "priceEnergy", "number"
    attribute "priceTax", "number"
    attribute "priceStartAt", "string"

    attribute "priceMinToday", "number"
    attribute "priceMaxToday", "number"
    attribute "priceMinAt", "string"
    attribute "priceMaxAt", "string"

    attribute "priceNextHour", "number"
    attribute "priceNextHourAt", "string"
    attribute "pricePlus2Hour", "number"
    attribute "pricePlus2HourAt", "string"

    attribute "consumption", "number"
    attribute "consumptionUnit", "string"
    attribute "lastUnitPrice", "number"
    attribute "lastUnitPriceVAT", "number"
    attribute "lastCost", "number"
    attribute "lastConsumptionFrom", "string"
    attribute "lastConsumptionTo", "string"
    attribute "statusText", "string"
  }

  preferences {
    input name: "accessToken", type: "password", title: "Tibber API Token", required: true
    input name: "pollInterval", type: "number", title: "Polling interval (minutes)", defaultValue: 60, range: "1..1440"
    input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
  }
}

def installed() {
  log.info "Installed"
  initialize()
}

def updated() {
  log.info "Updated"
  unschedule()
  initialize()
}

def initialize() {
  def interval = pollInterval?.toInteger() ?: 60
  if (interval < 60) {
    schedule("0 0/${interval} * * * ?", refresh)
  } else {
    def hours = (interval / 60).toInteger()
    schedule("0 0 0/${hours} * * ?", refresh)
  }
  refresh()
}

def refresh() {
  if (debugLogging) log.debug "Refreshing Tibber data..."
  pollPrice()
  pollConsumption()
}

def pollPrice() {
  def query = '{ viewer { homes { currentSubscription { priceInfo { current { total energy tax level currency startsAt } today { total startsAt } } } } } }'
  def body = [ query: query ]
  def params = [
    uri: 'https://api.tibber.com/v1-beta/gql',
    headers: [ 'Authorization': "Bearer ${accessToken}", 'Content-Type': 'application/json' ],
    body: new groovy.json.JsonBuilder(body).toString()
  ]
  try {
    httpPost(params) { resp ->
      def priceInfo = resp.data.data.viewer.homes[0].currentSubscription.priceInfo
      def current = priceInfo.current
      def today = priceInfo.today

      sendEvent(name: "currentPrice", value: round2(current.total))
      sendEvent(name: "priceEnergy", value: round2(current.energy))
      sendEvent(name: "priceTax", value: round2(current.tax))
      sendEvent(name: "priceLevel", value: current.level)
      sendEvent(name: "priceCurrency", value: current.currency)
      sendEvent(name: "priceStartAt", value: current.startsAt)
      sendEvent(name: "statusText", value: current.level)

      if (current.level == "CHEAP") {
        sendEvent(name: "presence", value: "present")
        sendEvent(name: "temperature", value: 1)
      } else if (current.level == "NORMAL") {
        sendEvent(name: "presence", value: "not present")
        sendEvent(name: "temperature", value: 2)
      } else {
        sendEvent(name: "presence", value: "not present")
        sendEvent(name: "temperature", value: 3)
      }

      def minPrice = today.min { it.total }
      def maxPrice = today.max { it.total }
      sendEvent(name: "priceMinToday", value: round2(minPrice.total))
      sendEvent(name: "priceMaxToday", value: round2(maxPrice.total))
      sendEvent(name: "priceMinAt", value: minPrice.startsAt)
      sendEvent(name: "priceMaxAt", value: maxPrice.startsAt)

      def cleanedDate = current.startsAt.replaceFirst(/([+-]\d{2}):(\d{2})$/, '$1$2')
      def now = new Date().parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", cleanedDate)
      def next = today.find { it.startsAt.startsWith(now.plus(1).format("yyyy-MM-dd'T'HH")) }
      def plus2 = today.find { it.startsAt.startsWith(now.plus(2).format("yyyy-MM-dd'T'HH")) }

      if (next) {
        sendEvent(name: "priceNextHour", value: round2(next.total))
        sendEvent(name: "priceNextHourAt", value: next.startsAt)
      }
      if (plus2) {
        sendEvent(name: "pricePlus2Hour", value: round2(plus2.total))
        sendEvent(name: "pricePlus2HourAt", value: plus2.startsAt)
      }
    }
  } catch (e) {
    log.error "Exception in pollPrice: ${e.message}"
  }
}

def pollConsumption() {
  def query = '{ viewer { homes { consumption(resolution: HOURLY, last: 1) { nodes { from to cost unitPrice unitPriceVAT consumption consumptionUnit } } } } }'
  def body = [ query: query ]
  def params = [
    uri: 'https://api.tibber.com/v1-beta/gql',
    headers: [ 'Authorization': "Bearer ${accessToken}", 'Content-Type': 'application/json' ],
    body: new groovy.json.JsonBuilder(body).toString()
  ]
  try {
    httpPost(params) { resp ->
      def node = resp.data.data.viewer.homes[0].consumption.nodes[0]

      sendEvent(name: "consumption", value: round2(node.consumption))
      sendEvent(name: "consumptionUnit", value: node.consumptionUnit)
      sendEvent(name: "lastUnitPrice", value: round2(node.unitPrice))
      sendEvent(name: "lastUnitPriceVAT", value: round2(node.unitPriceVAT))
      sendEvent(name: "lastCost", value: round2(node.cost))
      sendEvent(name: "lastConsumptionFrom", value: node.from)
      sendEvent(name: "lastConsumptionTo", value: node.to)

      def watts = (node.consumption != null) ? round2(node.consumption * 1000) : 0
      sendEvent(name: "power", value: watts)
    }
  } catch (e) {
    log.error "Exception in pollConsumption: ${e.message}"
  }
}

def round2(val) {
  return val == null ? null : Math.round(val * 100) / 100.0
}
