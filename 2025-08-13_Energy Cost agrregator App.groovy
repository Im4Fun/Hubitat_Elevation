/**
 *  Hubitat Energy Cost Aggregator (App)
 *
 *  Author: Carl Rådetorp
 *  Version: 1.0.1
 *  Date: 2025-08-13
 *
 *  Description:
 *    Aggregates energy usage from selected devices and estimates cost
 *    using either a fixed price per kWh or a dynamic price read from
 *    another device (e.g., Tibber). Optionally creates a child
 *    "Energy Summary" device to expose totals for dashboards.
 *
 *  Changelog:
 *   1.0.1 - Fix child device namespace + send numeric attribute values
 *   1.0.0 - Initial release ready for production
 */

import groovy.transform.Field
@Field static final String CHILD_DNI = "energy-summary-child"

/******************** APP METADATA ********************/

definition(
  name:        "Energy Cost Aggregator",
  namespace:   "calle",
  author:      "Carl Rådetorp",
  description: "Collect energy from devices and compute cost from fixed or device (Tibber) price.",
  category:    "Green Living",
  iconUrl:     "https://raw.githubusercontent.com/hubitatcommunity/HubitatPublic/master/icons/Energy.png",
  iconX2Url:   "https://raw.githubusercontent.com/hubitatcommunity/HubitatPublic/master/icons/Energy@2x.png",
  singleInstance: true
)

preferences {
  page(name: "mainPage")
  page(name: "breakdownPage")
}

/********************** UI PAGES **********************/

def mainPage() {
  dynamicPage(name: "mainPage", title: "Energy Cost Aggregator", uninstall: true, install: true) {
    section("Devices to Track") {
      input name: "energyMeters", type: "capability.energyMeter", title: "Select Energy Meter Devices", multiple: true, required: true
      input name: "alsoTrackPower", type: "bool", title: "Also subscribe to power (W) events?", defaultValue: false
    }
    section("Price Source") {
      input name: "currency", type: "text", title: "Currency", defaultValue: "SEK", required: true
      input name: "priceMode", type: "enum", title: "Choose price source", defaultValue: "fixed",
            options: ["fixed":"Fixed price per kWh", "device":"Read from device attribute"], submitOnChange: true
      if (normalizePriceMode() == "fixed") {
        input name: "fixedPrice", type: "decimal", title: "Fixed price per kWh", required: true, defaultValue: 1.0
      } else {
        input name: "priceDevice", type: "capability.sensor", title: "Device providing price", required: true
        input name: "priceAttribute", type: "text", title: "Attribute name (numeric)", required: true, defaultValue: "currentPrice"
      }
      input name: "includeTaxesFees", type: "decimal", title: "Extra cost per kWh (optional)", required: false
    }
    section("Time Windows & Resets") {
      input name: "rollDailyAt",   type: "time", title: "Daily roll-up time (default midnight)", required: false
      input name: "rollMonthlyOn", type: "enum", title: "Monthly roll-up day (1-28)", options: (1..28), defaultValue: 1, required: false
      input name: "rollMonthlyAt", type: "time", title: "Monthly roll-up time (default 00:05)", required: false
    }
    section("Summary & View") {
      input name: "createChild", type: "bool", title: "Create/Update Energy Summary child device?", defaultValue: true
      input name: "decimalsKWh", type: "number", title: "kWh decimals", defaultValue: 3
      input name: "decimalsCost", type: "number", title: "Cost decimals", defaultValue: 2
      href(name: "toBreakdown", title: "View current breakdown and totals", page: "breakdownPage", description: "Open live report")
    }
    section("Debug") {
      input name: "logEnable", type: "bool", title: "Enable debug logging (30 min)", defaultValue: false
    }
  }
}

def breakdownPage() {
  dynamicPage(name: "breakdownPage", title: "Current Breakdown & Totals") {
    section("") { paragraph getHtmlReport() }
  }
}

/********************* LIFECYCLE *********************/

def installed() { initialize() }

def updated() { unsubscribe(); unschedule(); initialize() }


def initialize() {
  if (logEnable) runIn(1800, "logsOff")

  state.prevEnergy  = state.prevEnergy  ?: [:]
  state.todayEnergy = state.todayEnergy ?: [:]
  state.monthEnergy = state.monthEnergy ?: [:]

  energyMeters?.each { dev ->
    subscribe(dev, "energy", "energyHandler")
    if (alsoTrackPower) subscribe(dev, "power", "powerHandler")
    if (!state.prevEnergy[dev.id as String]) {
      state.prevEnergy[dev.id as String] = bd(dev.currentValue("energy"))
    }
  }

  // Subscribe to price changes if using device source
  if (normalizePriceMode() == "device" && priceDevice && priceAttribute) {
    try { subscribe(priceDevice, priceAttribute.toString(), "priceAttrHandler") } catch (e) { log.warn "Price attribute subscribe failed: ${e}" }
  }

  // Scheduling
  scheduleDailyRollup()
  scheduleMonthlyRollup()

  if (createChild) ensureChild()
}

/******************* EVENT HANDLERS ******************/

def energyHandler(evt) {
  if (logEnable) log.debug "energy evt from ${evt.displayName}: ${evt.value} ${evt.unit ?: 'kWh'}"
  def id = evt.device.id as String
  BigDecimal newCum = bd(evt.value)
  BigDecimal prev   = bd(state.prevEnergy[id])
  BigDecimal delta  = newCum - prev
  if (delta < 0G) delta = 0G
  state.prevEnergy[id] = newCum
  state.todayEnergy[id] = bd(state.todayEnergy[id]) + delta
  state.monthEnergy[id] = bd(state.monthEnergy[id]) + delta
  updateChild()
}

def powerHandler(evt) {
  if (logEnable) log.debug "power evt from ${evt.displayName}: ${evt.value} W"
}

def priceAttrHandler(evt) {
  if (logEnable) log.debug "price attr changed ${evt.displayName}.${evt.name} = ${evt.value}"
  updateChild()
}

/*********************** SCHEDULING *******************/

def scheduleDailyRollup() {
  if (rollDailyAt) {
    schedule(timeToday(rollDailyAt, location?.timeZone ?: TimeZone.getTimeZone("UTC")), dailyRollup)
  } else {
    schedule("0 0 0 * * ?", dailyRollup) // midnight local time
  }
}

def scheduleMonthlyRollup() {
  Integer day = (settings?.rollMonthlyOn as Integer) ?: 1
  if (rollMonthlyAt) {
    def t = timeToday(rollMonthlyAt, location?.timeZone ?: TimeZone.getTimeZone("UTC"))
    String hh = t.format('H', location?.timeZone)
    String mm = t.format('m', location?.timeZone)
    schedule("0 ${mm} ${hh} ${day} * ?", monthlyRollup)
  } else {
    schedule("0 5 0 ${day} * ?", monthlyRollup) // 00:05 on selected day
  }
}

def appButtonHandler(btn) {
  if (btn == "resetToday")  resetTodayTotals()
  if (btn == "resetMonth")  resetMonthTotals()
}

def dailyRollup()  { if (logEnable) log.debug "Daily rollup";  resetTodayTotals() }

def monthlyRollup(){ if (logEnable) log.debug "Monthly rollup"; resetMonthTotals() }

def resetTodayTotals() { state.todayEnergy = [:]; updateChild() }

def resetMonthTotals() { state.monthEnergy = [:]; updateChild() }

/************************ HELPERS *********************/

def logsOff() { logEnable = false; log.info "Debug logging disabled" }

// Convert to BigDecimal safely; default 0G
BigDecimal bd(val) {
  try {
    if (val == null) return 0G
    if (val instanceof BigDecimal) return val
    return new BigDecimal(val.toString())
  } catch (e) { return 0G }
}

int decK() {
  try { Math.max(0, Math.min(6, (settings?.decimalsKWh as Integer) ?: 3)) } catch (e) { 3 }
}
int decC() {
  try { Math.max(0, Math.min(4, (settings?.decimalsCost as Integer) ?: 2)) } catch (e) { 2 }
}

String normalizePriceMode() {
  def pm = (settings?.priceMode ?: "fixed").toString().toLowerCase()
  if (pm.contains("device")) return "device"
  if (pm == "id") return (priceDevice && priceAttribute) ? "device" : "fixed" // backward fix
  return "fixed"
}

def currentPricePerKwh() {
  BigDecimal base
  if (normalizePriceMode() == "device") {
    def v = priceDevice?.currentValue(priceAttribute?.toString())
    base = bd(v)
  } else {
    base = bd(fixedPrice)
  }
  return (base ?: 0G) + bd(includeTaxesFees)
}

BigDecimal sumMapBD(Map m) {
  if (!m) return 0G
  m.values()?.inject(0G) { BigDecimal a, v -> a + bd(v) } as BigDecimal
}

def totals() {
  BigDecimal tDay   = sumMapBD(state.todayEnergy)
  BigDecimal tMonth = sumMapBD(state.monthEnergy)
  BigDecimal price  = currentPricePerKwh() ?: 0G
  int dk = decK(); int dc = decC()
  [
    todayKwh:  tDay.setScale(dk, BigDecimal.ROUND_HALF_UP),
    monthKwh:  tMonth.setScale(dk, BigDecimal.ROUND_HALF_UP),
    price:     price.setScale(Math.max(dc, 3), BigDecimal.ROUND_HALF_UP),
    todayCost: (tDay * price).setScale(dc, BigDecimal.ROUND_HALF_UP),
    monthCost: (tMonth * price).setScale(dc, BigDecimal.ROUND_HALF_UP)
  ]
}

def getHtmlReport() {
  def t = totals(); def c = currency ?: "SEK"
  String html = """
  <style>
    .eca {font-family:sans-serif}
    .eca .kpi{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;margin:8px 0}
    .eca .card{border:1px solid #ddd;border-radius:8px;padding:8px;background:#fafafa}
    .eca table{width:100%;border-collapse:collapse}
    .eca th,.eca td{border:1px solid #ddd;padding:6px;text-align:left}
    .eca th{background:#f5f5f5}
  </style>
  <div class='eca'>
    <div class='kpi'>
      <div class='card'><b>Current price</b><div>${t.price} ${c}/kWh</div></div>
      <div class='card'><b>Today</b><div>${t.todayKwh} kWh</div><div>≈ ${t.todayCost} ${c}</div></div>
      <div class='card'><b>This month</b><div>${t.monthKwh} kWh</div><div>≈ ${t.monthCost} ${c}</div></div>
    </div>
    <table>
      <tr><th>Device</th><th>Today (kWh)</th><th>Month (kWh)</th></tr>
      ${energyMeters?.sort{it.displayName.toLowerCase()}?.collect{ d ->
          def id=d.id as String; BigDecimal td=bd(state.todayEnergy[id]); BigDecimal md=bd(state.monthEnergy[id]);
          "<tr><td>${d.displayName}</td><td>${td.setScale(decK(), BigDecimal.ROUND_HALF_UP)}</td><td>${md.setScale(decK(), BigDecimal.ROUND_HALF_UP)}</td></tr>"
        }?.join('') ?: ''}
    </table>
  </div>
  """
  return html
}

/**************** CHILD SUMMARY DEVICE ****************/

def ensureChild() {
  def dni = makeChildDNI()
  if (!getChildDevice(dni)) {
    // FIX: use the same namespace as the driver ("calle")
    addChildDevice("calle", "Energy Summary Device", dni, [label: "Energy Summary", isComponent: true])
  }
  updateChild()
}

def updateChild() {
  try {
    if (!createChild) return
    def child = getChildDevice(makeChildDNI()); if (!child) return
    def t = totals(); def c = currency ?: "SEK"; int dk = decK(); int dc = decC()
    // Send NUMERIC values for NUMBER attributes
    child?.sendEvent(name: "todayEnergy", value: (t.todayKwh.setScale(dk, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: "kWh")
    child?.sendEvent(name: "monthEnergy", value: (t.monthKwh.setScale(dk, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: "kWh")
    child?.sendEvent(name: "price", value: (t.price as BigDecimal), unit: "${c}/kWh")
    child?.sendEvent(name: "todayCost", value: (t.todayCost.setScale(dc, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: c)
    child?.sendEvent(name: "monthCost", value: (t.monthCost.setScale(dc, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: c)
    // lastUpdated is a STRING
    child?.sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getTimeZone("UTC")))
  } catch (e) { log.warn "Unable to update child: ${e}" }
}

def makeChildDNI() { "${app.id}-${CHILD_DNI}" }
