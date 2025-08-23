/**
 * Tibber Smart Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Version: 1.0.5
 *  Date: 2025-08-23
 *
 *  Description:
 *  Polls Tibber for electricity prices and consumption data using the Tibber API.
 *  Tracks and reports current price, price level, next hour prices, consumption, and price metadata.
 *  Mirrors price level to presence and temperature for Easy Dashboard compatibility.
 *  Designed for rule automation and dashboard display with minimal UI clutter.
 *
 *  Change log:
 *  1.0.0 - Initial release
 *  1.0.1 - Dashboard mapping: presence + temperature = priceLevel
 *  1.0.2 - Timestamp parsing now uses milliseconds (SSSZ)
 *  1.0.3 - Fixed null error in consumption
 *  1.0.4 - Fixed invalid cron schedule for 60+ minute intervals
 *  1.0.5 - Added Fixed cost (per kWh) and applied to cost-related values
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
    input name: "fixedCost", type: "number", title: "Fixed cost (per kWh)", defaultValue: 0, required: false
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

      def fc = (fixedCost ?: 0) as BigDecimal

      // Raw reference values from Tibber
      sendEvent(name: "priceEnergy", value: round2(current.energy))
      sendEvent(name: "priceTax", value: round2(current.tax))
      sendEvent(name: "priceLevel", value: current.level)
      sendEvent(name: "priceCurrency", value: current.currency)
      sendEvent(name: "priceStartAt", value: current.startsAt)
      sendEvent(name: "statusText", value: current.level)

      // Adjusted totals (add fixed cost per kWh)
      sendEvent(name: "currentPrice", value: round2(((current.total ?: 0) as BigDecimal) + fc))

      def minPrice = today.min { it.total }
      def maxPrice = today.max { it.total }
      sendEvent(name: "priceMinToday", value: round2(((minPrice.total ?: 0) as BigDecimal) + fc))
      sendEvent(name: "priceMaxToday", value: round2(((maxPrice.total ?: 0) as BigDecimal) + fc))
      sendEvent(name: "priceMinAt", value: minPrice.startsAt)
      sendEvent(name: "priceMaxAt", value: maxPrice.startsAt)

      // Keep your colon→no-colon offset cleanup for parsing
      def cleanedDate = current.startsAt.replaceFirst(/([+-]\d{2}):(\d{2})$/, '$1$2')
      def now = new Date().parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", cleanedDate)
      def next = today.find { it.startsAt.startsWith(now.plus(1).format("yyyy-MM-dd'T'HH")) }
      def plus2 = today.find { it.startsAt.startsWith(now.plus(2).format("yyyy-MM-dd'T'HH")) }

      if (next) {
        sendEvent(name: "priceNextHour", value: round2(((next.total ?: 0) as BigDecimal) + fc))
        sendEvent(name: "priceNextHourAt", value: next.startsAt)
      }
      if (plus2) {
        sendEvent(name: "pricePlus2Hour", value: round2(((plus2.total ?: 0) as BigDecimal) + fc))
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

      def fc = (fixedCost ?: 0) as BigDecimal

      sendEvent(name: "consumption", value: round2(node.consumption))
      sendEvent(name: "consumptionUnit", value: node.consumptionUnit)

      // Adjust unit prices by fixed cost
      def adjUnit = (node.unitPrice != null) ? round2(((node.unitPrice as BigDecimal) + fc)) : null
      def adjUnitVAT = (node.unitPriceVAT != null) ? round2(((node.unitPriceVAT as BigDecimal) + fc)) : null
      sendEvent(name: "lastUnitPrice", value: adjUnit)
      sendEvent(name: "lastUnitPriceVAT", value: adjUnitVAT)

      // Adjust cost as consumption * (unitPrice + fixedCost)
      def adjustedCost = (node.consumption != null && node.unitPrice != null) ?
        round2((node.consumption as BigDecimal) * ((node.unitPrice as BigDecimal) + fc)) : round2(node.cost)
      sendEvent(name: "lastCost", value: adjustedCost)

      sendEvent(name: "lastConsumptionFrom", value: node.from)
      sendEvent(name: "lastConsumptionTo", value: node.to)

      // Mirror to Power (W)
      def watts = (node.consumption != null) ? round2((node.consumption as BigDecimal) * 1000) : 0
      sendEvent(name: "power", value: watts)
    }
  } catch (e) {
    log.error "Exception in pollConsumption: ${e.message}"
  }
}

def round2(val) {
  return val == null ? null : Math.round((val as BigDecimal) * 100) / 100.0
}
