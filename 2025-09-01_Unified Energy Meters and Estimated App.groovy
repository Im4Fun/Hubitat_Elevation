/**
 *  Unified Energy — Meters + Estimated (Bulbs & Switches)
 *
 *  Features
 *   • Time-of-use accurate costs: each ΔkWh is multiplied by the price valid at that moment.
 *   • Price source: fixed or from device attribute (e.g., Tibber) + optional extra cost/kWh.
 *   • Tracks BOTH: real meters and estimated bulbs/switches in one app.
 *   • Periodic tick (1/5/15 min) to accrue energy between events.
 *   • Daily/monthly rollovers.
 *   • Optional "Energy Summary Device" child (PowerMeter + EnergyMeter + custom attrs).
 *
 *  Author: Carl Rådetorp
 *  Version: 1.0.0
 *  Date: 2025-09-01
 *
 *  Notes
 *   • Keep tick at 1 minute for best TOU accuracy.
 *   • You can use ONLY meters, ONLY estimated devices, or a mix of both.
 */

import groovy.transform.Field
@Field static final String CHILD_DNI_SUFFIX = "unified-energy-summary-child"

/******************** APP METADATA ********************/

definition(
  name:        "Unified Energy (Meters + Estimated)",
  namespace:   "calle",
  author:      "Carl Rådetorp",
  description: "Aggregate real meters & estimate bulbs/switches; TOU cost.",
  category:    "Green Living",
  iconUrl:     "https://raw.githubusercontent.com/hubitatcommunity/HubitatPublic/master/icons/Energy.png",
  iconX2Url:   "https://raw.githubusercontent.com/hubitatcommunity/HubitatPublic/master/icons/Energy@2x.png",
  singleInstance: true
)

preferences {
  page(name: "mainPage")
  page(name: "deviceConfigPage")
  page(name: "breakdownPage")
}

/********************** UI PAGES **********************/

def mainPage() {
  ensureState()
  dynamicPage(name: "mainPage", title: "Unified Energy (Meters + Estimated)", uninstall: true, install: true) {

    section("Real devices with energy readings") {
      input name: "energyMeters", type: "capability.energyMeter", title: "Select Energy Meter devices", multiple: true, required: false, submitOnChange: true
      input name: "alsoTrackPower", type: "bool", title: "Also subscribe to power (W) events?", defaultValue: false
      paragraph "These devices report cumulative kWh. The app attributes ΔkWh at the current price."
    }

    section("Bulbs & switches without meters (estimated)") {
      input name: "tracked", type: "capability.switch", title: "Select bulbs/switches to estimate", multiple: true, required: false, submitOnChange: true
      paragraph "Include dimmers; if a device reports level, power can scale with level when ON."
      href(name: "toPerDevice", title: "Configure per-device standby/max W", page: "deviceConfigPage",
           description: tracked ? "Configure ${tracked?.size()} devices" : "Select devices above")
    }

    section("Update frequency") {
      input name: "tickMinutes", type: "enum", title: "Periodic update interval",
        options: ["1":"Every 1 minute","5":"Every 5 minutes","15":"Every 15 minutes"], defaultValue: "1"
    }

    section("Price Source") {
      input name: "currency", type: "text", title: "Currency", defaultValue: "SEK", required: true
      input name: "priceMode", type: "enum", title: "Choose price source", defaultValue: "fixed",
            options: ["fixed":"Fixed price per kWh", "device":"Read from device attribute"], submitOnChange: true
      if (normalizePriceMode() == "fixed") {
        input name: "fixedPrice", type: "decimal", title: "Fixed price per kWh", required: true, defaultValue: 1.0
      } else {
        input name: "priceDevice", type: "capability.sensor", title: "Device providing price (e.g., Tibber)", required: true
        input name: "priceAttribute", type: "text", title: "Attribute name (numeric)", required: true, defaultValue: "currentPrice"
      }
      input name: "includeTaxesFees", type: "decimal", title: "Extra cost per kWh (optional)", required: false
      paragraph "Costs are integrated over time: ΔkWh × price_at_that_time, then summed for today/month."
    }

    section("Rollover scheduling") {
      input name: "rollDailyAt",   type: "time", title: "Daily roll-up time (default midnight)", required: false
      input name: "rollMonthlyOn", type: "enum", title: "Monthly roll-up day (1-28)", options: (1..28), defaultValue: 1, required: false
      input name: "rollMonthlyAt", type: "time", title: "Monthly roll-up time (default 00:05)", required: false
    }

    section("Summary & view") {
      input name: "createChild", type: "bool", title: "Create/Update Energy Summary child device?", defaultValue: true
      input name: "childLabel",  type: "text", title: "Child device label", defaultValue: "Energy Summary", required: false
      input name: "pushOnTick",  type: "bool", title: "Update child every tick", defaultValue: true
      input name: "decimalsKWh", type: "number", title: "kWh decimals", defaultValue: 3
      input name: "decimalsCost", type: "number", title: "Cost decimals", defaultValue: 2
      href(name: "toBreakdown", title: "View current breakdown & totals", page: "breakdownPage", description: "Open live report")
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

def deviceConfigPage() {
  ensureState()
  dynamicPage(name: "deviceConfigPage", title: "Per-device power settings (Estimated group)") {
    tracked?.each { dev ->
      def id = dev.id as String
      section("${dev.displayName}") {
        input name: key("standbyW", id), type: "decimal", title: "Standby power (W)", required: true, defaultValue: 0.5
        input name: key("maxW", id),     type: "decimal", title: "Maximum power (W)", required: true
        input name: key("scaleWithLevel", id), type: "bool",
              title: "Scale with level when ON (if device reports level)", defaultValue: dev.hasAttribute("level")
      }
    }
  }
}

def breakdownPage() {
  ensureState()
  dynamicPage(name: "breakdownPage", title: "Current Breakdown & Totals") {
    section("") { paragraph getHtmlReport() }
  }
}

/********************* LIFECYCLE *********************/

def installed() { initialize() }
def updated()  { unsubscribe(); unschedule(); initialize() }

def initialize() {
  if (logEnable) runIn(1800, "logsOff")
  ensureState()

  // Price
  state.lastPrice = currentPricePerKwh() ?: 0G
  state.lastPriceTs = now()

  // --- Real meters ---
  energyMeters?.each { dev ->
    subscribe(dev, "energy", "energyHandler")
    if (alsoTrackPower) subscribe(dev, "power", "powerHandler")
    def id = dev.id as String
    if (!state.prevEnergy[id]) {
      state.prevEnergy[id] = bd(dev.currentValue("energy")) // seed
    }
  }

  // --- Estimated switches/dimmers ---
  tracked?.each { dev ->
    subscribe(dev, "switch", "switchHandler")
    if (dev.hasAttribute("level")) subscribe(dev, "level", "levelHandler")
    def id = dev.id as String
    if (!state.lastTs[id]) state.lastTs[id] = now()
    state.lastPower[id] = estimateWatts(dev)
  }

  // Price updates
  if (normalizePriceMode() == "device" && priceDevice && priceAttribute) {
    try { subscribe(priceDevice, priceAttribute.toString(), "priceChanged") }
    catch (e) { log.warn "Price attribute subscribe failed: ${e}" }
  }

  // Schedules
  scheduleTick()
  scheduleDailyRollup()
  scheduleMonthlyRollup()

  if (createChild) ensureChild()
  updateChild() // initial push
}

/******************* EVENT HANDLERS ******************/

// --------- Real meters ---------

def energyHandler(evt) {
  if (logEnable) log.debug "energy evt from ${evt.displayName}: ${evt.value} ${evt.unit ?: 'kWh'}"
  def id = evt.device.id as String
  BigDecimal newCum = bd(evt.value)
  BigDecimal prev   = bd(state.prevEnergy[id])
  BigDecimal delta  = newCum - prev
  if (delta < 0G) delta = 0G
  state.prevEnergy[id] = newCum

  BigDecimal price = currentPricePerKwh() ?: 0G
  // Per-device
  state.todayEnergyMeters[id] = bd(state.todayEnergyMeters[id]) + delta
  state.monthEnergyMeters[id] = bd(state.monthEnergyMeters[id]) + delta
  // Totals
  state.todayCost = bd(state.todayCost) + (delta * price)
  state.monthCost = bd(state.monthCost) + (delta * price)

  updateChild()
}

def powerHandler(evt) {
  if (logEnable) log.debug "power evt from ${evt.displayName}: ${evt.value} W"
  def id = evt.device.id as String
  try {
    state.meterPower[id] = bd(evt.value)
  } catch (e) { state.meterPower[id] = 0G }
  if (pushOnTick) updateChild()
}

// --------- Estimated devices ---------

def switchHandler(evt) {
  if (logEnable) log.debug "switch evt ${evt.displayName}: ${evt.value}"
  accrueEstimated() // accrue using current lastPrice
  def dev = evt.device
  state.lastPower[dev.id as String] = estimateWatts(dev)
  if (pushOnTick) updateChild()
}

def levelHandler(evt) {
  if (logEnable) log.debug "level evt ${evt.displayName}: ${evt.value}"
  accrueEstimated()
  def dev = evt.device
  state.lastPower[dev.id as String] = estimateWatts(dev)
  if (pushOnTick) updateChild()
}

// --------- Price ---------

def priceChanged(evt) {
  if (logEnable) log.debug "price evt ${evt.displayName}: ${evt.value} (${evt.unit ?: ''})"
  // Accrue estimated up to NOW at the OLD price
  accrueEstimated()
  // Switch to NEW price for future ΔkWh (meters will use current price per event/tick)
  state.lastPrice = currentPricePerKwh() ?: 0G
  state.lastPriceTs = now()
  if (pushOnTick) updateChild()
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
  // Poll/attribute meters between events at the CURRENT price
  BigDecimal price = currentPricePerKwh() ?: 0G
  energyMeters?.each { dev ->
    def id = dev.id as String
    BigDecimal currentCum = bd(dev.currentValue("energy"))
    BigDecimal prev       = bd(state.prevEnergy[id])
    BigDecimal delta      = currentCum - prev
    if (delta <= 0G) { state.prevEnergy[id] = currentCum; return }
    state.prevEnergy[id] = currentCum
    state.todayEnergyMeters[id] = bd(state.todayEnergyMeters[id]) + delta
    state.monthEnergyMeters[id] = bd(state.monthEnergyMeters[id]) + delta
    state.todayCost       = bd(state.todayCost) + (delta * price)
    state.monthCost       = bd(state.monthCost) + (delta * price)
    if (logEnable) log.debug "tick meters ${dev.displayName}: Δ=${delta.setScale(6, BigDecimal.ROUND_HALF_UP)} kWh @ ${price}"
  }

  // Accrue estimated group (at state.lastPrice for that interval)
  accrueEstimated()

  // Refresh baselines for estimated
  tracked?.each { dev ->
    state.lastPower[dev.id as String] = estimateWatts(dev)
    state.lastTs[dev.id as String] = now()
  }

  
if (alsoTrackPower) {
  (energyMeters ?: []).each { d ->
    def id = d.id as String
    try {
      BigDecimal w = bd(d.currentValue("power"))
      if (w != null) state.meterPower[id] = w
    } catch (e) { /* ignore */ }
  }
}

if (pushOnTick) updateChild()
}

/*********************** SCHEDULING *******************/

def scheduleDailyRollup() {
  if (rollDailyAt) {
    schedule(timeToday(rollDailyAt, location?.timeZone ?: TimeZone.getTimeZone("UTC")), dailyRollup)
  } else {
    schedule("0 0 0 * * ?", dailyRollup) // midnight local
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

def dailyRollup()  { if (logEnable) log.debug "Daily rollup";  resetTodayTotals(); updateChild() }
def monthlyRollup(){ if (logEnable) log.debug "Monthly rollup"; resetMonthTotals(); updateChild() }

def resetTodayTotals() {
  state.todayEnergyMeters = [:]
  state.todayKwhEstimated = [:]
  state.todayCost   = 0G
}
def resetMonthTotals() {
  state.monthEnergyMeters = [:]
  state.monthKwhEstimated = [:]
  state.monthCost   = 0G
}

/*********************** CORE LOGIC *******************/

/**
 * Accrue energy for estimated devices since their lastTs, and
 * add TOU cost using state.lastPrice for the same interval.
 */
def accrueEstimated() {
  long nowMs = now()
  BigDecimal totalIncKWh = 0G

  (tracked ?: []).each { dev ->
    def id = dev.id as String
    long prev  = (state.lastTs[id] as Long) ?: nowMs
    BigDecimal lastW = bd(state.lastPower[id])
    long dtMs = nowMs - prev
    if (dtMs <= 0L) { state.lastTs[id] = nowMs; return }

    BigDecimal hours = new BigDecimal(dtMs).divide(new BigDecimal(3600000), 10, BigDecimal.ROUND_HALF_UP)
    BigDecimal incKWh = lastW.multiply(hours).divide(new BigDecimal(1000), 10, BigDecimal.ROUND_HALF_UP)

    // Per-device energy
    state.todayKwhEstimated[id] = bd(state.todayKwhEstimated[id]) + incKWh
    state.monthKwhEstimated[id] = bd(state.monthKwhEstimated[id]) + incKWh

    // Move device timestamp forward
    state.lastTs[id] = nowMs

    totalIncKWh += incKWh
    if (logEnable) log.debug "Accrued est ${dev.displayName}: +${incKWh.setScale(6, BigDecimal.ROUND_HALF_UP)} kWh (dt=${dtMs}ms, P=${lastW}W)"
  }

  // TOU Cost for estimated block at the price that was valid for the interval
  if (totalIncKWh > 0G) {
    BigDecimal price = bd(state.lastPrice)
    BigDecimal incCost = totalIncKWh.multiply(price).setScale(6, BigDecimal.ROUND_HALF_UP)
    state.todayCost = bd(state.todayCost) + incCost
    state.monthCost = bd(state.monthCost) + incCost
  }
}

// Estimate instantaneous watts from current device state and per-device config
BigDecimal estimateWatts(dev) {
  def id = dev.id as String
  BigDecimal s = max0(bd(settings[key("standbyW", id)]))
  BigDecimal m = max0(bd(settings[key("maxW", id)]))
  boolean scale = (settings[key("scaleWithLevel", id)] as Boolean) ?: false

  String sw = (dev.currentValue("switch") ?: "off").toString().toLowerCase()
  if (sw != "on") return s

  if (scale && dev.hasAttribute("level")) {
    BigDecimal lvl = max0(bd(dev.currentValue("level")))
    if (lvl > 100G) lvl = 100G
    return s + (m - s) * (lvl / 100G)
  }
  return m
}

// Total instantaneous watts across estimated devices
BigDecimal currentEstimatedWatts() {
  BigDecimal sum = 0G
  (tracked ?: []).each { d -> sum += estimateWatts(d) }
  return sum
}

/************************* UI/REPORT ******************/

def totals() {
  // kWh
  BigDecimal tDayMeters   = sumMapBD(state.todayEnergyMeters)
  BigDecimal tMonthMeters = sumMapBD(state.monthEnergyMeters)
  BigDecimal tDayEst      = sumMapBD(state.todayKwhEstimated)
  BigDecimal tMonthEst    = sumMapBD(state.monthKwhEstimated)

  BigDecimal tDayK   = (tDayMeters + tDayEst)
  BigDecimal tMonthK = (tMonthMeters + tMonthEst)

  BigDecimal priceNow = currentPricePerKwh()
  int dk = decK()
  int dc = decC()

  return [
    todayKwh : tDayK.setScale(dk, BigDecimal.ROUND_HALF_UP),
    monthKwh : tMonthK.setScale(dk, BigDecimal.ROUND_HALF_UP),
    todayCost: bd(state.todayCost).setScale(dc, BigDecimal.ROUND_HALF_UP),
    monthCost: bd(state.monthCost).setScale(dc, BigDecimal.ROUND_HALF_UP),
    price    : priceNow?.setScale(Math.max(dc, 3), BigDecimal.ROUND_HALF_UP),
    currency : (currency ?: "SEK"),
    todayKwhMeters: tDayMeters.setScale(dk, BigDecimal.ROUND_HALF_UP),
    todayKwhEst:    tDayEst.setScale(dk, BigDecimal.ROUND_HALF_UP)
  ]
}

def getHtmlReport() {
  ensureState()
  def t = totals()
  String c = t.currency

  String headerCards = """
  <div class='kpi'>
    ${t.price != null ? "<div class='card'><b>Current price</b><div>${t.price} ${c}/kWh</div></div>" : ""}
    <div class='card'><b>Today</b><div>${t.todayKwh} kWh</div><div>≈ ${t.todayCost} ${c}</div></div>
    <div class='card'><b>This month</b><div>${t.monthKwh} kWh</div><div>≈ ${t.monthCost} ${c}</div></div>
    <div class='card'><b>Breakdown</b><div>Today: ${t.todayKwhMeters} kWh (meters) + ${t.todayKwhEst} kWh (estimated)</div></div>
    <div class='card'><b>Now</b>
  <div><b>Total:</b> ${ (currentEstimatedWatts() + currentMetersWatts()).setScale(1, BigDecimal.ROUND_HALF_UP) } W</div>
  <div><small>Meters:</small> ${ currentMetersWatts().setScale(1, BigDecimal.ROUND_HALF_UP) } W</div>
  <div><small>Estimated:</small> ${ currentEstimatedWatts().setScale(1, BigDecimal.ROUND_HALF_UP) } W</div>
</div>
  </div>"""

  int dk = decK()

  String rowsMeters = (energyMeters ?: [])
    .sort { it.displayName.toLowerCase() }
    .collect { d ->
      def id = d.id as String
      BigDecimal td = bd(state.todayEnergyMeters[id]).setScale(dk, BigDecimal.ROUND_HALF_UP)
      BigDecimal md = bd(state.monthEnergyMeters[id]).setScale(dk, BigDecimal.ROUND_HALF_UP)
      "<tr><td>${d.displayName}</td><td>—</td><td>—</td><td>—</td><td>—</td><td>—</td><td>${td}</td><td>${md}</td></tr>"
    }?.join("") ?: ""

  String rowsEst = (tracked ?: [])
    .sort { it.displayName.toLowerCase() }
    .collect { d ->
      def id = d.id as String
      BigDecimal td = bd(state.todayKwhEstimated[id]).setScale(dk, BigDecimal.ROUND_HALF_UP)
      BigDecimal md = bd(state.monthKwhEstimated[id]).setScale(dk, BigDecimal.ROUND_HALF_UP)
      String sw = (d.currentValue("switch") ?: "off").toString()
      String lvl = d.hasAttribute("level") ? (d.currentValue("level") ?: "—").toString() : "—"
      BigDecimal est = estimateWatts(d).setScale(1, BigDecimal.ROUND_HALF_UP)
      BigDecimal sW = bd(settings[key("standbyW", id)]).setScale(1, BigDecimal.ROUND_HALF_UP)
      BigDecimal mW = bd(settings[key("maxW", id)]).setScale(1, BigDecimal.ROUND_HALF_UP)
      "<tr><td>${d.displayName}</td><td>${sw}</td><td>${lvl}</td><td>${sW}</td><td>${mW}</td><td>${est}</td><td>${td}</td><td>${md}</td></tr>"
    }?.join("") ?: ""

  return """
  <style>
    .unifiedE {font-family:sans-serif}
    .unifiedE .kpi{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:8px;margin:8px 0}
    .unifiedE .card{border:1px solid #ddd;border-radius:8px;padding:8px;background:#fafafa}
    .unifiedE table{width:100%;border-collapse:collapse;margin-top:6px}
    .unifiedE th,.unifiedE td{border:1px solid #ddd;padding:6px;text-align:left}
    .unifiedE th{background:#f5f5f5}
    .unifiedE .subhdr{background:#f0f0f0;font-weight:bold}
  </style>
  <div class='unifiedE'>
    ${headerCards}
    <table>
      <tr>
        <th>Device</th><th>Switch</th><th>Level</th>
        <th>Standby W</th><th>Max W</th><th>Est. W now</th>
        <th>Today (kWh)</th><th>Month (kWh)</th>
      </tr>
      <tr class='subhdr'><td colspan='8'>Real meters</td></tr>
      ${rowsMeters}
      <tr class='subhdr'><td colspan='8'>Estimated bulbs & switches</td></tr>
      ${rowsEst}
    </table>
  </div>
  """
}

/**************** CHILD SUMMARY DEVICE ****************/

def ensureChild() {
  def dni = makeChildDNI()
  def label = childLabel ?: "Energy Summary"
  def child = getChildDevice(dni)
  if (!child) {
    addChildDevice("calle", "Energy Summary Device", dni, [label: label, isComponent: true])
  } else if (label && child.label != label) {
    child.label = label
  }
}



BigDecimal currentMetersWatts() {
  BigDecimal sum = 0G
  (energyMeters ?: []).each { d ->
    def id = d.id as String
    BigDecimal w = bd(state.meterPower[id])
    if (w == 0G || w == null) {
      try { w = bd(d.currentValue("power")) } catch (e) { /* ignore */ }
    }
    sum += max0(w)
  }
  return sum
}

def updateChild() {
  try {
    if (!createChild) return

def child = getChildDevice(makeChildDNI()); if (!child) return
def t = totals(); def c = currency ?: "SEK"; int dk = decK(); int dc = decC()

BigDecimal estW   = currentEstimatedWatts().setScale(1, BigDecimal.ROUND_HALF_UP)
BigDecimal meterW = currentMetersWatts().setScale(1, BigDecimal.ROUND_HALF_UP)
BigDecimal totalW = (estW + meterW).setScale(1, BigDecimal.ROUND_HALF_UP)

// Power capability = TOTAL instantaneous power (meters + estimated)
child?.sendEvent(name: "power", value: totalW as BigDecimal, unit: "W")
child?.sendEvent(name: "powerEstimated", value: estW as BigDecimal, unit: "W")
child?.sendEvent(name: "powerMeters",    value: meterW as BigDecimal, unit: "W")

    // Standard capabilities for dashboards & Watchtower
    // Power: only meaningful for estimated group; meters may have native devices for power already.
    BigDecimal powerNow = currentEstimatedWatts().setScale(1, BigDecimal.ROUND_HALF_UP)
    BigDecimal monthKwh = t.monthKwh.setScale(dk, BigDecimal.ROUND_HALF_UP)

    // PowerMeter
    child?.sendEvent(name: "energy",      value: monthKwh as BigDecimal, unit: "kWh")      // EnergyMeter

    // Custom summary attributes
    child?.sendEvent(name: "todayEnergy", value: (t.todayKwh.setScale(dk, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: "kWh")
    child?.sendEvent(name: "monthEnergy", value: (t.monthKwh.setScale(dk, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: "kWh")
    child?.sendEvent(name: "price",       value: (t.price as BigDecimal), unit: "${c}/kWh")
    child?.sendEvent(name: "todayCost",   value: (bd(state.todayCost).setScale(dc, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: c)
    child?.sendEvent(name: "monthCost",   value: (bd(state.monthCost).setScale(dc, BigDecimal.ROUND_HALF_UP) as BigDecimal), unit: c)
    child?.sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getTimeZone("UTC")))
  } catch (e) { log.warn "Unable to update child: ${e}" }
}

def makeChildDNI() { "${app.id}-${CHILD_DNI_SUFFIX}" }

/************************ HELPERS *********************/

String key(String prefix, String id) { "${prefix}_${id}" }

def logsOff() { logEnable = false; log.info "Debug logging disabled" }

def ensureState() {
  state.meterPower = state.meterPower ?: [:]
// Real meters
  state.prevEnergy        = state.prevEnergy        ?: [:]
  state.todayEnergyMeters = state.todayEnergyMeters ?: [:]
  state.monthEnergyMeters = state.monthEnergyMeters ?: [:]
  // Estimated
  state.lastTs            = state.lastTs            ?: [:]
  state.lastPower         = state.lastPower         ?: [:]
  state.todayKwhEstimated = state.todayKwhEstimated ?: [:]
  state.monthKwhEstimated = state.monthKwhEstimated ?: [:]
  // Totals
  state.todayCost         = state.todayCost         ?: 0G
  state.monthCost         = state.monthCost         ?: 0G
  state.lastPrice         = state.lastPrice         ?: 0G
  state.lastPriceTs       = state.lastPriceTs       ?: now()
}

BigDecimal bd(val) {
  try {
    if (val == null) return 0G
    if (val instanceof BigDecimal) return val
    return new BigDecimal(val.toString())
  } catch (e) { return 0G }
}

BigDecimal max0(BigDecimal v) { v < 0G ? 0G : v }

int decK() { try { Math.max(0, Math.min(6, (settings?.decimalsKWh as Integer) ?: 3)) } catch (e) { 3 } }
int decC() { try { Math.max(0, Math.min(4, (settings?.decimalsCost as Integer) ?: 2)) } catch (e) { 2 } }

BigDecimal sumMapBD(Map m) {
  if (!m) return 0G
  m.values()?.inject(0G) { BigDecimal a, v -> a + bd(v) } as BigDecimal
}

String normalizePriceMode() {
  String m = (settings?.priceMode ?: "fixed").toString().toLowerCase()
  return (m in ["fixed","device"]) ? m : "fixed"
}

// Returns current (base + extra) or null if not configured
BigDecimal currentPricePerKwh() {
  BigDecimal base = null
  if (normalizePriceMode() == "fixed") {
    if (settings?.fixedPrice != null) base = bd(settings.fixedPrice)
  } else {
    try {
      if (priceDevice && priceAttribute) {
        def raw = priceDevice.currentValue(priceAttribute.toString())
        if (raw != null) base = bd(raw)
      }
    } catch (e) { log.warn "Failed to read price from ${priceDevice} attr ${priceAttribute}: ${e}" }
  }
  if (base == null) return null
  BigDecimal extra = bd(settings?.includeTaxesFees)
  if (extra < 0G) extra = 0G
  return (base + extra)
}
