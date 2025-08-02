/**
 * Crime Monitor SE for Hubitat Elevation
 *
 *  Author: Carl Rådetorp
 *  Version: 1.1.4
 *  Date: 2025-07-25
 *
 *  Description:
 *  Polls brottsplatskartan.se for crime events by area name.
 *  If a new event is reported, it turns on a switch and resets it after a chosen time (seconds).
 *  Manual toggling is supported via on/off commands.
 *  Only triggers on new events, not duplicates.
 *
 *  Changelog:
 *  * 1.1.4 - Removed City/Region option, replaced with single area field. Switched reset timer to seconds.
 */

metadata {
    definition(name: "Crime Monitor SE", namespace: "calle", author: "Carl Rådetorp") {
        capability "Switch"
        capability "Polling"
        capability "Refresh"
        attribute "lastTitle", "string"
        attribute "lastTime", "string"
        attribute "lastLocation", "string"
        attribute "lastUrl", "string"
    }

    preferences {
        input "areaName", "text", title: "Area to monitor", required: true
        input "pollFrequency", "enum", title: "Polling frequency (minutes)", options: ["5", "10", "15", "30", "60"], defaultValue: "15"
        input "resetSeconds", "number", title: "Seconds to keep switch ON after activity", defaultValue: 600
        input "enableDebugLogging", "bool", title: "Enable debug logging?", defaultValue: false
        input "enableInfoLogging", "bool", title: "Enable info logging?", defaultValue: true
    }
}

def installed() {
    if (enableDebugLogging) log.debug "Installed: waiting for preferences"
}

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    if (enableDebugLogging) log.debug "Initializing driver"
    if (areaName) {
        schedulePolling()
        poll()
    } else if (enableDebugLogging) {
        log.debug "Skipping poll: areaName is not set"
    }
}

def schedulePolling() {
    def freq = (pollFrequency ?: "15").toInteger()
    if (enableDebugLogging) log.debug "Scheduling polling every ${freq} minutes"
    schedule("0 */${freq} * ? * *", poll)
}

def refresh() {
    if (enableDebugLogging) log.debug "Manual refresh triggered"
    poll()
}

def poll() {
    if (!areaName) {
        if (enableDebugLogging) log.debug "Skipping poll: missing areaName"
        return
    }

    if (enableDebugLogging) log.debug "Polling brottsplatskartan for area: ${areaName}"

    def apiUrl = "https://brottsplatskartan.se/api/events?location=${URLEncoder.encode(areaName, 'UTF-8')}"

    try {
        httpGet([uri: apiUrl, contentType: "application/json"]) { resp ->
            if (resp.status == 200) {
                def events = resp.data?.data
                if (events instanceof List && events.size() > 0) {
                    def filtered = events.findAll { evt ->
                        def loc = evt?.location_string?.toLowerCase() ?: ""
                        def titleLoc = evt?.title_location?.toLowerCase() ?: ""
                        return loc.contains(areaName.toLowerCase()) || titleLoc.contains(areaName.toLowerCase())
                    }

                    if (filtered.size() > 0) {
                        def first = filtered[0]
                        def currentId = first?.id?.toString()
                        def lastId = state.lastEventId

                        if (currentId && currentId != lastId) {
                            state.lastEventId = currentId

                            def title = first?.headline ?: "Unknown event"
                            def timestamp = first?.pubdate_iso8601 ?: new Date().format("yyyy-MM-dd HH:mm:ss")
                            def location = first?.location_string ?: "Unknown location"
                            def url = first?.permalink ?: "https://brottsplatskartan.se"

                            sendEvent(name: "lastTitle", value: title)
                            sendEvent(name: "lastTime", value: timestamp)
                            sendEvent(name: "lastLocation", value: location)
                            sendEvent(name: "lastUrl", value: url)

                            if (enableInfoLogging) {
                                log.info "New event: ${title} at ${location} (${timestamp})"
                            }

                            turnOnSwitch()
                        } else {
                            if (enableDebugLogging) log.debug "No new event detected (ID: ${currentId})"
                        }
                    } else {
                        if (enableDebugLogging) log.debug "No matching events for '${areaName}'"
                    }
                } else {
                    log.warn "Unexpected API structure or no recent events"
                }
            } else {
                log.warn "Unexpected response status: ${resp.status}"
            }
        }
    } catch (e) {
        log.error "Error during API call: ${e.message}"
    }
}

def turnOnSwitch() {
    if (device.currentValue("switch") != "on") {
        sendEvent(name: "switch", value: "on")
        if (enableDebugLogging) log.debug "Switch turned on from new event"
    }
    runIn((resetSeconds ?: 600), turnOffSwitch)
}

def turnOffSwitch() {
    if (device.currentValue("switch") != "off") {
        sendEvent(name: "switch", value: "off")
        if (enableDebugLogging) log.debug "Switch turned off after delay"
    }
}

def on() {
    def current = device.currentValue("switch")
    def newState = (current == "on") ? "off" : "on"
    sendEvent(name: "switch", value: newState)
    if (enableDebugLogging) log.debug "Manual toggle: switch set to ${newState}"
    if (newState == "on") {
        runIn((resetSeconds ?: 600), turnOffSwitch)
    }
}

def off() {
    on() // toggle behavior
}
