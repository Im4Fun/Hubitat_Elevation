/**
 *  Hubitat Energy Cost Aggregator — TOU-accurate
 *
 *  Author: Carl Rådetorp
 *  Version: 1.4.1
 *  Date: 2025-08-19
 *
 *  What it does
 *    • Aggregates energy from selected EnergyMeter devices.
 *    • Computes cost using time-of-use: cost += ΔkWh * price_at_that_moment.
 *    • Price source: fixed or from device attribute (e.g., Tibber) + optional extra cost/kWh.
 *    • Daily/monthly rollovers. Optional child “Energy Summary Device”.
 *
 *  Notes
 *    • Keep tick at 1 min for best TOU accuracy between device events.
 */

import groovy.transform.Field
@Field static final String CHILD_DNI_SUFFIX = "energy-summary-child"

/******************** APP METADATA ********************/

definition(
  name:        "Energy Cost Aggregator",
  namespace:   "calle",
  author:      "Carl Rådetorp",
  description: "Aggregates energy from meters and computes time-of-use accurate cost from fixed or device (Tibber) price.",
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
  ensureState()
  dynamicPage(name: "mainPage", title: "Energy Cost Aggregator", uninstall: true, install: true) {

    section("Devices to Track") {
      input name: "energyMeters", type: "capability.energyMeter", title: "Select Energy Meter Devices", multiple: true, required: true, submitOnChange: true
      input name: "alsoTrackPower", type: "bool", title: "Also subscribe to power (W) events?", defaultValue: false
    }

    section("Update frequency") {
      input name: "tickMinutes", type: "enum", title: "Periodic read interval (helps TOU accuracy between events)",
        options: ["1":"Every 1 minute","5":"Every 5 minutes","15":"Every 15 minutes"], defaultValue: "1"
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
      paragraph "Costs are integrated over time: each ΔkWh is multiplied by the price valid at that moment."
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

    section("Actions") {
      input name: "resetToday", type: "button", title: "Reset TODAY totals"
      input name: "resetMonth", type: "button", title: "Reset MONTH totals"
      input name: "manualPush", type: "button", title: "Push totals to child now"
    }

    section("Debug") {
      input name: "logEnable", type: "bool", title: "Enable debug logging (30 min)", defaultValue: false
    }
  }
}

def breakdownPage() {
  ensureState()
  dynamicPage(name: "breakdownPage", title: "Current Breakdown & Totals") {
    section("") { paragraph getHtmlReport() }   // No action buttons here
  }
}

/********************* LIFECYCLE *********************/

def installed() { initialize() }
def updated()  { unsubscribe(); unschedule(); initialize() }

def initialize() {
  if (logEnable) runIn(1800, "logsOff")
  ensureState()

  // Subscribe devices
  energyMeters?.each { dev ->
    subscribe(dev, "energy", "energyHandler")
    if (alsoTrackPower) subscribe(dev, "power", "powerHandler")
    def id = dev.id as String
    if (!state.prevEnergy[id]) {
      state.prevEnergy[id] = bd(dev.currentValue("energy"))  // seed from current cumulative reading
    }
  }

  // Subscribe to price changes if using device source
  if (normalizePriceMode() == "device" && priceDevice && priceAttribute) {
    try { subscribe(priceDevice, priceAttribute.toString(), "priceAttrHandler") }
    catch (e) { log.warn "Price attribute subscribe failed: ${e}" }
  }

  // Periodic tick for TOU attribution between events
  scheduleTick()

  // Rollovers
  scheduleDailyRollup()
  scheduleMonthlyRollup()

  if (createChild) ensureChild()
  updateChild() // initial push
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

  // Attribute ΔkWh at price right now
  BigDecimal price = currentPricePerKwh() ?: 0G
  state.todayEnergy[id] = bd(state.todayEnergy[id]) + delta
  state.monthEnergy[id] = bd(state.monthEnergy[id]) + delta
  state.todayCost       = bd(state.todayCost) + (delta * price)
  state.monthCost       = bd(state.monthCost) + (delta * price)

  updateChild()
}

def powerHandler(evt) {
  if (logEnable) log.debug "power evt from ${evt.displayName}: ${evt.value} W"
}

def priceAttrHandler(evt) {
  if (logEnable) log.debug "price attr changed ${evt.displayName}.${evt.name} = ${evt.value}"
  // No retroactive recalc; future ΔkWh will use the new price.
  updateChild()
}

/********************* PERIODIC TICK ******************/

def scheduleTick() {
  switch ((settings?.tickMinutes ?: "1").toString()) {
    case "1":  runEvery1Minute("tick");  break
    case "5":  runEvery5Minutes("tick"); break
    case "15": runEvery15Minutes("tick"); break
    default:   runEvery1Minute("tick")
  }
}

def tick() {
  // Poll each meter's cumulative energy and attribute ΔkWh at current price
  BigDecimal price = currentPricePerKwh() ?: 0G

  energyMeters?.each { dev ->
    def id = dev.id as String
    BigDecimal currentCum = bd(dev.currentValue("energy"))
    BigDecimal prev       = bd(state.prevEnergy[id])
    BigDecimal delta      = currentCum - prev
    if (delta <= 0G) { state.prevEnergy[id] = currentCum; return }
    state.prevEnergy[id] = currentCum

    state.todayEnergy[id] = bd(state.todayEnergy[id]) + delta
    state.monthEnergy[id] = bd(state.monthEnergy[id]) + delta
    state.todayCost       = bd(state.todayCost) + (delta * price)
    state.monthCost       = bd(state.monthCost) + (delta * price)

    if (logEnable) log.debug "tick accrued ${dev.displayName}: Δ=${delta.setScale(6, BigDecimal.ROUND_HALF_UP)} kWh @ ${price} → +${(delta*price).setScale(4, BigDecimal.ROUND_HALF_UP)}"
  }

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
  if (btn == "manualPush")  updateChild()
}

def dailyRollup()  { if (logEnable) log.debug "Daily rollup";  resetTodayTotals() }
def monthlyRollup(){ if (logEnable) log.debug "Monthly rollup"; resetMonthTotals() }

def resetTodayTotals() {
  state.todayEnergy = [:]
  state.todayCost   = 0G
  updateChild()
}
def resetMonthTotals() {
  state.monthEnergy = [:]
  state.monthCost   = 0G
  updateChild()
}

/************************ RENDERING *******************/

def getHtmlReport() {
  def t = totals(); def c = currency ?: "SEK"
  int dk = decK()

  String headerCards = """
  <div class='kpi'>
    <div class='card'><b>Current price</b><div>${t.price} ${c}/kWh</div></div>
    <div class='card'><b>Today</b><div>${t.todayKwh} kWh</div><div>≈ ${t.todayCost} ${c}</div></div>
    <div class='card'><b>This month</b><div>${t.monthKwh} kWh</div><div>≈ ${t.monthCost} ${c}</div></div>
  </div>"""

  String rows = (energyMeters ?: [])
    .sort { it.displayName.toLowerCase() }
    .collect { d ->
      def id = d.id as String
      BigDecimal td = bd(state.todayEnergy[id]).setScale(dk, BigDecimal.ROUND_HALF_UP)
      BigDecimal md = bd(state.monthEnergy[id]).setScale(dk, BigDecimal.ROUND_HALF_UP)
      "<tr><td>${d.displayName}</td><td>${td}</td><td>${md}</td></tr>"
    }?.join("") ?: ""

  return """
  <style>
    .eca {font-family:sans-serif}
    .eca .kpi{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;margin:8px 0}
    .eca .card{border:1px solid #ddd;border-radius:8px;padding:8px;background:#fafafa}
    .eca table{width:100%;border-collapse:collapse}
    .eca th,.eca td{border:1px solid #ddd;padding:6px;text-align:left}
    .eca th{background:#f5f5f5}
  </style>
  <div class='eca'>
    ${headerCards}
    <table>
      <tr><th>Device</th><th>Today (kWh)</th><th>Month (kWh)</th></tr>
      ${rows}
    </table>
  </div>
  """
}

/**************** CHILD SUMMARY DEVICE ****************/

def ensureChild() {
  def dni = makeChildDNI()
  if (!getChildDevice(dni)) {
    addChildDevice("calle", "Energy Summary Device", dni, [label: "Energy Summary", isComponent: true])
  }
}

def updateChild() {
  try {
    if (!createChild) return
    def child = getChildDevice(makeChildDNI()); if (!child) return
    def t = totals(); def c = currency ?: "SEK"; int dk = decK(); int dc = decC()
    child?.sendEvent(name: "todayEnergy", value: (t.todayKwh.setScale(dk, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: "kWh")
    child?.sendEvent(name: "monthEnergy", value: (t.monthKwh.setScale(dk, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: "kWh")
    child?.sendEvent(name: "price", value: (t.price as BigDecimal), unit: "${c}/kWh")
    child?.sendEvent(name: "todayCost", value: (bd(state.todayCost).setScale(dc, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: c)
    child?.sendEvent(name: "monthCost", value: (bd(state.monthCost).setScale(dc, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: c)
    child?.sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getTimeZone("UTC")))
  } catch (e) { log.warn "Unable to update child: ${e}" }
}

def makeChildDNI() { "${app.id}-${CHILD_DNI_SUFFIX}" }

/************************ HELPERS *********************/

def logsOff() { logEnable = false; log.info "Debug logging disabled" }

def ensureState() {
  state.prevEnergy  = state.prevEnergy  ?: [:]
  state.todayEnergy = state.todayEnergy ?: [:]
  state.monthEnergy = state.monthEnergy ?: [:]
  state.todayCost   = state.todayCost   ?: 0G
  state.monthCost   = state.monthCost   ?: 0G
}

BigDecimal bd(val) {
  try {
    if (val == null) return 0G
    if (val instanceof BigDecimal) return val
    return new BigDecimal(val.toString())
  } catch (e) { return 0G }
}

int decK() { try { Math.max(0, Math.min(6, (settings?.decimalsKWh as Integer) ?: 3)) } catch (e) { 3 } }
int decC() { try { Math.max(0, Math.min(4, (settings?.decimalsCost as Integer) ?: 2)) } catch (e) { 2 } }

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
  BigDecimal extra = bd(includeTaxesFees)
  if (extra < 0G) extra = 0G
  return (base ?: 0G) + extra
}

BigDecimal sumMapBD(Map m) {
  if (!m) return 0G
  m.values()?.inject(0G) { BigDecimal a, v -> a + bd(v) } as BigDecimal
}

def totals() {
  BigDecimal tDay   = sumMapBD(state.todayEnergy)
  BigDecimal tMonth = sumMapBD(state.monthEnergy)
  int dk = decK(); int dc = decC()
  [
    todayKwh:  tDay.setScale(dk, BigDecimal.ROUND_HALF_UP),
    monthKwh:  tMonth.setScale(dk, BigDecimal.ROUND_HALF_UP),
    todayCost: bd(state.todayCost).setScale(dc, BigDecimal.ROUND_HALF_UP),
    monthCost: bd(state.monthCost).setScale(dc, BigDecimal.ROUND_HALF_UP),
    price:     (currentPricePerKwh() ?: 0G).setScale(Math.max(dc, 3), BigDecimal.ROUND_HALF_UP)
  ]
}
