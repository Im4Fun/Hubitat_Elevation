/**
 * SMHI Weather Driver for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Version: 1.3.1
 *  Date: 2025-07-21
 *
 *  Description:
 *   - Supports temperature, humidity, pressure, wind and dew point
 *   - Optional to use custom coordinates
 *   - Includes forecast for tomorrow
 *   - Calculates dew point for today and tomorrow
 *   - Manual refresh capability
 *
 *  Changelog:
 *   1.3.1 - Rounded dew point values to two decimals.
 *   1.3.0 - Added refresh() for manual polling.
 *   1.2.0 - Added dew point calculation.
 *   1.1.0 - Added support for tomorrow's forecast.
 *   1.0.0 - Initial public release.
 */

metadata {
    definition (name: "SMHI Weather Driver", namespace: "calle", author: "Carl Rådetorp") {
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"
        capability "Pressure Measurement"
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        attribute "wind", "number"
        attribute "windGust", "number"
        attribute "precip_rate", "number"
        attribute "lastPollTime", "string"
        attribute "latitude", "decimal"
        attribute "longitude", "decimal"

        attribute "temperatureTomorrow", "number"
        attribute "humidityTomorrow", "number"
        attribute "pressureTomorrow", "number"
        attribute "windTomorrow", "number"
        attribute "windGustTomorrow", "number"
        attribute "precip_rateTomorrow", "number"

        attribute "dewPoint", "number"
        attribute "dewPointTomorrow", "number"
    }

    preferences {
        input name: "txtEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "gpsCoords", type: "bool", title: "Use custom coordinates?", defaultValue: false
        input name: "latitudeCust", type: "decimal", title: "Custom Latitude", required: false
        input name: "longitudeCust", type: "decimal", title: "Custom Longitude", required: false
        input name: "pollInterval", type: "enum", title: "Polling Interval", options: ["5 Minutes", "15 Minutes", "30 Minutes", "1 Hour"], defaultValue: "15 Minutes"
    }
}

def installed() {
    initialize()
}

def updated() {
    log.info "Preferences updated"
    unschedule()
    initialize()
}

def initialize() {
    switch (pollInterval) {
        case "5 Minutes":
            runEvery5Minutes(poll)
            break
        case "15 Minutes":
            runEvery15Minutes(poll)
            break
        case "30 Minutes":
            runEvery30Minutes(poll)
            break
        case "1 Hour":
            runEvery1Hour(poll)
            break
        default:
            runEvery15Minutes(poll)
    }
    poll()
}

def refresh() {
    if (txtEnable) log.debug "Manual refresh triggered"
    poll()
}

def poll() {
    if (txtEnable) log.debug "Starting poll..."
    def lat = gpsCoords ? latitudeCust : location.latitude
    def lon = gpsCoords ? longitudeCust : location.longitude

    sendEvent(name: "latitude", value: lat)
    sendEvent(name: "longitude", value: lon)

    def weather = getSMHIWeather(lat, lon)
    if (weather) {
        // Today
        sendEvent(name: "temperature", value: weather.temp, unit: "C")
        sendEvent(name: "humidity", value: weather.humidity, unit: "%")
        sendEvent(name: "pressure", value: weather.pressure, unit: "hPa")
        sendEvent(name: "wind", value: weather.windSpeed, unit: "m/s")
        sendEvent(name: "windGust", value: weather.windGust, unit: "m/s")
        sendEvent(name: "precip_rate", value: weather.precip, unit: "mm")

        def dewPoint = calculateDewPoint(weather.temp, weather.humidity)
        sendEvent(name: "dewPoint", value: dewPoint, unit: "C")

        // Tomorrow
        sendEvent(name: "temperatureTomorrow", value: weather.tempTomorrow, unit: "C")
        sendEvent(name: "humidityTomorrow", value: weather.humidityTomorrow, unit: "%")
        sendEvent(name: "pressureTomorrow", value: weather.pressureTomorrow, unit: "hPa")
        sendEvent(name: "windTomorrow", value: weather.windSpeedTomorrow, unit: "m/s")
        sendEvent(name: "windGustTomorrow", value: weather.windGustTomorrow, unit: "m/s")
        sendEvent(name: "precip_rateTomorrow", value: weather.precipTomorrow, unit: "mm")

        def dewPointTomorrow = calculateDewPoint(weather.tempTomorrow, weather.humidityTomorrow)
        sendEvent(name: "dewPointTomorrow", value: dewPointTomorrow, unit: "C")

        def now = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        sendEvent(name: "lastPollTime", value: now)
        if (txtEnable) log.debug "Poll complete at ${now}"
    } else {
        log.warn "SMHI weather data not available."
    }
}

def getSMHIWeather(lat, lon) {
    def url = "https://opendata-download-metfcst.smhi.se/api/category/pmp3g/version/2/geotype/point/lon/${lon}/lat/${lat}/data.json"
    def result = [:]

    try {
        httpGet(url) { resp ->
            if (resp?.status == 200) {
                def data = resp.data
                def timeSeries = data.timeSeries

                def now = new Date()
                def tomorrow = new Date(now.time + (24 * 60 * 60 * 1000))
                def todayEntry = timeSeries?.find { it.validTime.startsWith(now.format("yyyy-MM-dd'T'HH")) }
                def tomorrowEntry = timeSeries?.find { it.validTime.startsWith(tomorrow.format("yyyy-MM-dd'T'12")) } // ~12:00 tomorrow

                if (todayEntry) {
                    def p = todayEntry.parameters
                    result.temp = getParamValue(p, "t")
                    result.humidity = getParamValue(p, "r")
                    result.pressure = getParamValue(p, "msl")
                    result.windSpeed = getParamValue(p, "ws")
                    result.windGust = getParamValue(p, "gust")
                    result.precip = getParamValue(p, "pmean") ?: 0
                }

                if (tomorrowEntry) {
                    def pT = tomorrowEntry.parameters
                    result.tempTomorrow = getParamValue(pT, "t")
                    result.humidityTomorrow = getParamValue(pT, "r")
                    result.pressureTomorrow = getParamValue(pT, "msl")
                    result.windSpeedTomorrow = getParamValue(pT, "ws")
                    result.windGustTomorrow = getParamValue(pT, "gust")
                    result.precipTomorrow = getParamValue(pT, "pmean") ?: 0
                }
            }
        }
    } catch (Exception e) {
        log.error "Error fetching SMHI weather: ${e.message}"
        return null
    }
    return result
}

def getParamValue(params, name) {
    return params.find { it.name == name }?.values?.get(0)
}

def calculateDewPoint(tempC, humidity) {
    if (tempC == null || humidity == null) return null
    def a = 17.27
    def b = 237.7
    def alpha = ((a * tempC) / (b + tempC)) + Math.log(humidity / 100.0)
    def dp = (b * alpha) / (a - alpha)
    return (Math.round(dp * 100) / 100.0) // round to 2 decimal places
}
