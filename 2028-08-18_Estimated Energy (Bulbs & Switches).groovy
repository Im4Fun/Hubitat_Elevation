/**
 *  Estimated Energy (Bulbs & Switches)
 *  - Dynamic Price (fixed or device attr) + Extra Cost
 *  - Optional Child Summary Device (with PowerMeter & EnergyMeter-friendly events)
 *
 *  Author: Carl Rådetorp
 *  Version: 1.3.0
 *  Date: 2025-08-18
 *
 *  What it does:
 *    • Estimates instantaneous W for each selected bulb/switch from standby/max W and optional level scaling.
 *    • Integrates to kWh (today / month) by event + periodic tick.
 *    • Price can be fixed OR read from a device attribute (e.g., Tibber) with an added fixed extra/kWh.
 *    • Optional child summary device receives:
 *        - power (W, total instantaneous)  [PowerMeter]
 *        - energy (kWh, month total)       [EnergyMeter]
 *        - todayEnergy, monthEnergy, price, todayCost, monthCost, lastUpdated (custom)
 */

import groovy.transform.Field

/******************** APP METADATA ********************/
definition(
  name:        "Estimated Energy (Bulbs & Switches)",
  namespace:   "calle",
  author:      "Carl Rådetorp",
  description: "Estimate kWh for bulbs/switches from standby & max W with dynamic price + optional child summary device.",
  category:    "Green Living",
  iconUrl:     "",
  iconX2Url:   "",
  singleInstance: false
)

preferences {
  page(name: "mainPage")
  page(name: "deviceConfigPage")
  page(name: "breakdownPage")
}

/********************** UI PAGES **********************/

def mainPage() {
  ensureState()

  dynamicPage(name: "mainPage", title: "Estimated Energy (Bulbs & Switches)", uninstall: true, install: true) {

    section("Select devices to estimate") {
      input name: "tracked", type: "capability.switch", title: "Bulbs/Switches", multiple: true, required: true, submitOnChange: true
      paragraph "Include dimmers; if a device reports level, power can scale with level when ON."
      href(name: "toPerDevice", title: "Configure per-device standby/max W", page: "deviceConfigPage",
           description: tracked ? "Configure ${tracked?.size()} devices" : "Select devices above")
    }

    section("Update frequency") {
      input name: "tickMinutes", type: "enum", title: "Periodic update interval",
        options: ["1":"Every 1 minute","5":"Every 5 minutes","15":"Every 15 minutes"], defaultValue: "1"
    }

    section("Rollover scheduling") {
      input name: "rollDailyAt",   type: "time",  title: "Daily roll-up time (default midnight)", required: false
      input name: "rollMonthlyOn", type: "enum",  title: "Monthly roll-up day (1-28)", options: (1..28), defaultValue: 1, required: false
      input name: "rollMonthlyAt", type: "time",  title: "Monthly roll-up time (default 00:05)", required: false
    }

    section("Price Source") {
      input name: "currency",    type: "text",   title: "Currency", defaultValue: "SEK", required: true
      input name: "priceMode",   type: "enum",   title: "Choose price source", defaultValue: "fixed",
            options: ["fixed":"Fixed price per kWh", "device":"Read from device attribute"], submitOnChange: true
      if (normalizePriceMode() == "fixed") {
        input name: "fixedPrice", type: "decimal", title: "Fixed price per kWh", required: true, defaultValue: 1.0
      } else {
        input name: "priceDevice",    type: "capability.sensor", title: "Device providing price (e.g., Tibber)", required: true
        input name: "priceAttribute", type: "text",              title: "Attribute name (numeric)", required: true, defaultValue: "currentPrice"
      }
      input name: "includeTaxesFees", type: "decimal", title: "Extra cost per kWh (optional)", required: false
      paragraph "Total price used = Base price (fixed or from device) + Extra cost per kWh."
    }

    section("Summary child device (optional)") {
      input name: "createChild",   type: "bool",   title: "Create/keep summary child device", defaultValue: false, submitOnChange: true
      input name: "childLabel",    type: "text",   title: "Child device label", defaultValue: "Energy Summary – Bulbs", required: false
      input name: "pushOnTick",    type: "bool",   title: "Update child every tick", defaultValue: true
      if (getChildDevice(childDni())) {
        paragraph "<i>Child present:</i> ${getChildDevice(childDni())?.displayName}"
        input name: "deleteChildBtn", type: "button", title: "Delete Child Device"
      } else if (createChild) {
        paragraph "Child will be created on Save/Update."
      }
    }

    section("Display") {
      input name: "decimalsKWh", type: "number", title: "kWh decimals", defaultValue: 3
    }

    section("View report") {
      href(name: "toBreakdown", title: "Open live breakdown & totals", page: "breakdownPage", description: "View")
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
  dynamicPage(name: "deviceConfigPage", title: "Per-device power settings") {
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

  // Devices & baselines
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

  // Periodic accrual tick & rollovers
  scheduleTick()
  scheduleDailyRollup()
  scheduleMonthlyRollup()

  // Child handling
  if (settings?.createChild) {
    ensureChildPresent()
    updateChild() // initial push
  }
}

/******************* EVENT HANDLERS ******************/

def switchHandler(evt) {
  if (logEnable) log.debug "switch evt ${evt.displayName}: ${evt.value}"
  def dev = evt.device
  accrueFor(dev)
  state.lastPower[dev.id as String] = estimateWatts(dev)
  if (pushOnTick) updateChild()
}

def levelHandler(evt) {
  if (logEnable) log.debug "level evt ${evt.displayName}: ${evt.value}"
  def dev = evt.device
  accrueFor(dev)
  state.lastPower[dev.id as String] = estimateWatts(dev)
  if (pushOnTick) updateChild()
}

def priceChanged(evt) {
  if (logEnable) log.debug "price evt ${evt.displayName}: ${evt.value} (${evt.unit ?: ''})"
  if (pushOnTick) updateChild()
}

/********************* SCHEDULING *********************/

def scheduleTick() {
  switch ((settings?.tickMinutes ?: "1").toString()) {
    case "1":  runEvery1Minute("tick");  break
    case "5":  runEvery5Minutes("tick"); break
    case "15": runEvery15Minutes("tick"); break
    default:   runEvery1Minute("tick")
  }
}

def tick() {
  tracked?.each { dev -> accrueFor(dev) }
  tracked?.each { dev ->
    state.lastPower[dev.id as String] = estimateWatts(dev)
    state.lastTs[dev.id as String] = now()
  }
  if (pushOnTick) updateChild()
}

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

/******************* ROLLOVERS & BUTTONS **************/

def appButtonHandler(btn) {
  if (btn == "resetToday")  { resetTodayTotals();  updateChild() }
  if (btn == "resetMonth")  { resetMonthTotals();  updateChild() }
  if (btn == "manualPush")  { updateChild() }
  if (btn == "deleteChildBtn") { deleteChildIfExists() }
}

def dailyRollup()  { if (logEnable) log.debug "Daily rollup";  resetTodayTotals(); updateChild() }
def monthlyRollup(){ if (logEnable) log.debug "Monthly rollup"; resetMonthTotals(); updateChild() }

def resetTodayTotals() { state.todayKwh = [:] }
def resetMonthTotals() { state.monthKwh = [:] }

/*********************** CORE LOGIC *******************/

// Accrue energy for a single device using its lastPower and lastTs
def accrueFor(dev) {
  def id = dev.id as String
  long nowMs = now()
  long prev  = (state.lastTs[id] as Long) ?: nowMs
  BigDecimal lastW = bd(state.lastPower[id])
  long dtMs = nowMs - prev
  if (dtMs <= 0L) {
    state.lastTs[id] = nowMs
    return
  }
  BigDecimal hours = new BigDecimal(dtMs).divide(new BigDecimal(3600000), 10, BigDecimal.ROUND_HALF_UP)
  // Wh = W * h, kWh = Wh / 1000
  BigDecimal incKWh = lastW.multiply(hours).divide(new BigDecimal(1000), 10, BigDecimal.ROUND_HALF_UP)
  // Accumulate
  state.todayKwh[id] = bd(state.todayKwh[id]) + incKWh
  state.monthKwh[id] = bd(state.monthKwh[id]) + incKWh
  // Move timestamp forward
  state.lastTs[id] = nowMs
  if (logEnable) log.debug "Accrued ${dev.displayName}: +${incKWh.setScale(6, BigDecimal.ROUND_HALF_UP)} kWh (dt=${dtMs}ms, P=${lastW}W)"
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

// Total instantaneous watts across tracked devices
BigDecimal currentTotalWatts() {
  BigDecimal sum = 0G
  (tracked ?: []).each { d -> sum += estimateWatts(d) }
  return sum
}

/************************* UI/REPORT ******************/

def totals() {
  BigDecimal tDay   = sumMapBD(state.todayKwh)
  BigDecimal tMonth = sumMapBD(state.monthKwh)
  BigDecimal price  = currentPricePerKwh() // may be null
  int dk = decK()

  def out = [
    todayKwh: tDay.setScale(dk, BigDecimal.ROUND_HALF_UP),
    monthKwh: tMonth.setScale(dk, BigDecimal.ROUND_HALF_UP)
  ]

  if (price != null) {
    String c = currency ?: "SEK"
    out.price     = price.setScale(3, BigDecimal.ROUND_HALF_UP)
    out.todayCost = (tDay * price).setScale(2, BigDecimal.ROUND_HALF_UP)
    out.monthCost = (tMonth * price).setScale(2, BigDecimal.ROUND_HALF_UP)
    out.currency  = c
  }
  return out
}

def getHtmlReport() {
  ensureState()
  def t = totals()
  int dk = decK()
  String c = currency ?: "SEK"

  String headerCards = """
  <div class='kpi'>
    ${t.price != null ? "<div class='card'><b>Current price</b><div>${t.price} ${c}/kWh</div></div>" : ""}
    <div class='card'><b>Today</b><div>${t.todayKwh} kWh</div>${t.price != null ? "<div>≈ ${t.todayCost} ${c}</div>" : ""}</div>
    <div class='card'><b>This month</b><div>${t.monthKwh} kWh</div>${t.price != null ? "<div>≈ ${t.monthCost} ${c}</div>" : ""}</div>
    <div class='card'><b>Now</b><div>${currentTotalWatts().setScale(1, BigDecimal.ROUND_HALF_UP)} W</div></div>
  </div>"""

  String rows = (tracked ?: [])
    .sort{ it.displayName.toLowerCase() }
    .collect { d ->
      def id = d.id as String
      BigDecimal td = bd(state.todayKwh[id]).setScale(dk, BigDecimal.ROUND_HALF_UP)
      BigDecimal md = bd(state.monthKwh[id]).setScale(dk, BigDecimal.ROUND_HALF_UP)
      String sw = (d.currentValue("switch") ?: "off").toString()
      String lvl = d.hasAttribute("level") ? (d.currentValue("level") ?: "—").toString() : "—"
      BigDecimal est = estimateWatts(d).setScale(1, BigDecimal.ROUND_HALF_UP)
      BigDecimal sW = bd(settings[key("standbyW", id)]).setScale(1, BigDecimal.ROUND_HALF_UP)
      BigDecimal mW = bd(settings[key("maxW", id)]).setScale(1, BigDecimal.ROUND_HALF_UP)
      "<tr><td>${d.displayName}</td><td>${sw}</td><td>${lvl}</td><td>${sW}</td><td>${mW}</td><td>${est}</td><td>${td}</td><td>${md}</td></tr>"
    }?.join("") ?: ""

  return """
  <style>
    .estE {font-family:sans-serif}
    .estE .kpi{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;margin:8px 0}
    .estE .card{border:1px solid #ddd;border-radius:8px;padding:8px;background:#fafafa}
    .estE table{width:100%;border-collapse:collapse}
    .estE th,.estE td{border:1px solid #ddd;padding:6px;text-align:left}
    .estE th{background:#f5f5f5}
  </style>
  <div class='estE'>
    ${headerCards}
    <table>
      <tr>
        <th>Device</th><th>Switch</th><th>Level</th>
        <th>Standby W</th><th>Max W</th><th>Est. W now</th>
        <th>Today (kWh)</th><th>Month (kWh)</th>
      </tr>
      ${rows}
    </table>
  </div>
  """
}

/************************ PRICE LOGIC *****************/

String normalizePriceMode() {
  String m = (settings?.priceMode ?: "fixed").toString().toLowerCase()
  return (m in ["fixed","device"]) ? m : "fixed"
}

// Returns total price (base + extra) or null if not configured
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

/********************* CHILD DEVICE *******************/

// Driver expected: namespace "calle", typeName "Energy Summary Device".
// Recommend: add capability "PowerMeter"; capability "EnergyMeter"; and custom attributes used below.
String childDni() { "calle:energySummary:${app.id}" }

def ensureChildPresent() {
  try {
    def child = getChildDevice(childDni())
    if (!child) {
      child = addChildDevice("calle", "Energy Summary Device", childDni(), null,
        [label: (childLabel ?: "Energy Summary – Bulbs"), isComponent: false, name: "Energy Summary"])
      log.info "Created child device: ${child?.displayName}"
    } else if (child && childLabel && child.label != childLabel) {
      child.label = childLabel
    }
    return child
  } catch (e) {
    log.error "Failed to ensure child: ${e}"
    return null
  }
}

def deleteChildIfExists() {
  def child = getChildDevice(childDni())
  if (child) {
    try {
      deleteChildDevice(child.deviceNetworkId)
      log.info "Deleted child device."
    } catch (e) {
      log.error "Failed to delete child: ${e}"
    }
  }
}

Map childSummaryMap() {
  def t = totals()
  String ts = new Date().format('yyyy-MM-dd HH:mm:ss', location?.timeZone ?: TimeZone.getTimeZone('UTC'))
  [
    todayEnergy: (t.todayKwh ?: 0G),
    monthEnergy: (t.monthKwh ?: 0G),
    price:       (t.price    ?: 0G),
    todayCost:   (t.todayCost?: 0G),
    monthCost:   (t.monthCost?: 0G),
    lastUpdated: ts,
    powerNow:    currentTotalWatts().setScale(1, BigDecimal.ROUND_HALF_UP)   // W
  ]
}

// Generic component compatibility (some drivers call componentRefresh on parent)
def componentRefresh(cd) { updateChild() }

// Single method (no overloads) so Hubitat's Groovy is happy.
// Accepts optional arg (ignored) to be callable as parent.updateChild([:]) or parent.updateChild().
def updateChild(opts = null) {
  if (!settings?.createChild) return
  def child = ensureChildPresent()
  if (!child) return
  def m = childSummaryMap()
  try {
    // Custom summary attributes
    child.sendEvent(name:"todayEnergy", value: m.todayEnergy as BigDecimal, unit:"kWh")
    child.sendEvent(name:"monthEnergy", value: m.monthEnergy as BigDecimal, unit:"kWh")
    child.sendEvent(name:"price",       value: m.price as BigDecimal)
    child.sendEvent(name:"todayCost",   value: m.todayCost as BigDecimal)
    child.sendEvent(name:"monthCost",   value: m.monthCost as BigDecimal)
    child.sendEvent(name:"lastUpdated", value: m.lastUpdated)

    // Standard capabilities
    child.sendEvent(name:"power",  value: m.powerNow as BigDecimal, unit:"W")         // PowerMeter
    child.sendEvent(name:"energy", value: m.monthEnergy as BigDecimal, unit:"kWh")   // EnergyMeter (using monthly kWh)

    if (logEnable) log.debug "Child updated: ${m}"
  } catch (e) {
    log.warn "Failed to push to child: ${e}"
  }
}

/************************ HELPERS *********************/

String key(String prefix, String id) { "${prefix}_${id}" }

def logsOff() { logEnable = false; log.info "Debug logging disabled" }

// Ensure state maps exist
def ensureState() {
  state.lastTs    = state.lastTs    ?: [:]
  state.lastPower = state.lastPower ?: [:]
  state.todayKwh  = state.todayKwh  ?: [:]
  state.monthKwh  = state.monthKwh  ?: [:]
}

BigDecimal bd(val) {
  try {
    if (val == null) return 0G
    if (val instanceof BigDecimal) return val
    return new BigDecimal(val.toString())
  } catch (e) { return 0G }
}

BigDecimal max0(BigDecimal v) { v < 0G ? 0G : v }

int decK() {
  try { Math.max(0, Math.min(6, (settings?.decimalsKWh as Integer) ?: 3)) } catch (e) { 3 }
}

BigDecimal sumMapBD(Map m) {
  if (!m) return 0G
  m.values()?.inject(0G) { BigDecimal a, v -> a + bd(v) } as BigDecimal
}
