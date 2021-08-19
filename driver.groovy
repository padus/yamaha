/**
 * Driver:     Yamaha AVR
 * Author:     Mirco Caramori
 * Repository: https://github.com/padus/yamaha/tree/main
 * Import URL: https://raw.githubusercontent.com/padus/yamaha/main/driver.groovy
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 *
 */

public static String version() { return "v1.0.18"; }

/**
 * Change Log:
 *
 * 2021.04.10 - Initial implementation
 * 2021.08.18 - Relocated repository: mircolino -> padus
 */

// Metadata -------------------------------------------------------------------------------------------------------------------

metadata {
  definition(name: "Yamaha AVR", namespace: "mircolino", author: "Mirco Caramori", importUrl: "https://raw.githubusercontent.com/padus/yamaha/main/driver.groovy") {
    capability "Actuator";
    capability "Switch";
    capability "AudioVolume";
    capability "MediaInputSource";
    capability "Refresh";

 // command "on()";
 // command "off()";
 // command "mute()";
 // command "unmute()";
 // command "setVolume(BigDecimal val)";                        // 0-100
 // command "volumeUp()";
 // command "volumeDown()";
 // command "setInputSource(String name)";                      // "hdmi1"
 // command "refresh()";

 // attribute "switch", "enum", ["on", "off"];                  // "on", "off"
 // attribute "mute", "enum", ["muted", "unmuted"];             // "muted", "unmuted"
 // attribute "volume", "number";                               // 0-100 %
    attribute "decibels", "number";                             // -30.5 dB
 // attribute "supportedInputs", "string";                      // json object: ["hdmi1", "hdmi2", "hdmi3", "hdmi4", "hdmi5"]
 // attribute "mediaInputSource", "string";                     // "hdmi1"
    attribute "model", "string";                                // "TSR-7810"
    attribute "firmware", "number";                             // "v2.85"
  }

  preferences {
    input(name: "deviceAddress", type: "string", title: "<font style='font-size:12px; color:#1a77c9'>Address</font>", description: "<font style='font-size:12px; font-style: italic'>AVR IP address or hostname</font>", defaultValue: "", required: true);
    input(name: "deviceZone", type: "string", title: "<font style='font-size:12px; color:#1a77c9'>Zone</font>", description: "<font style='font-size:12px; font-style: italic'>AVR zone</font>", defaultValue: "main", required: true);
    input(name: "devicePolling", type: "number", title: "<font style='font-size:12px; color:#1a77c9'>Polling</font>", description: "<font style='font-size:12px; font-style: italic'>AVR status update interval in minutes (1-59, 0 to disable)</font>", defaultValue: 5, required: true);
    input(name: "volumeStep", type: "number", title: "<font style='font-size:12px; color:#1a77c9'>Volume Step</font>", description: "<font style='font-size:12px; font-style: italic'>Up/Down volume % step (1-10)</font>", defaultValue: 1, required: true);
    input(name: "logLevel", type: "enum", title: "<font style='font-size:12px; color:#1a77c9'>Log Verbosity</font>", description: "<font style='font-size:12px; font-style: italic'>Default: 'Debug' for 30 min and 'Info' thereafter</font>", options: [0:"Error", 1:"Warning", 2:"Info", 3:"Debug", 4:"Trace"], multiple: false, defaultValue: 3, required: true);
  }
}

/*
 * State variables used by the driver:
 *
 * zones                                                        // ArrayList<String> of available zones
 * volume                                                       // Map with min, max and step volume of the current zone (also used to determine if the current device/zone has been initialized)
 * 
 */

// Preferences -----------------------------------------------------------------------------------------------------------------

private String deviceAddress() {
  //
  // Return the device address, or "" if invalid
  //
  if (settings.deviceAddress != null)  return (settings.deviceAddress.toString());
  return ("");
}

// -------------------------------------------------------------

private String deviceZone() {
  //
  // Return the device zone, or "main" if invalid
  //
  if (settings.deviceZone != null)  return (settings.deviceZone.toString());
  return ("main");
}

// -------------------------------------------------------------

private Integer devicePolling() {
  //
  // Return the device polling interval in minutes, or 5 if invalid
  //
  if (settings.devicePolling != null) return (settings.devicePolling.toInteger());
  return (5);
}

// -------------------------------------------------------------

private Integer volumeStep() {
  //
  // Return the volume step, or 1 if invalid
  //
  if (settings.volumeStep != null)  return (settings.volumeStep.toInteger());
  return (1);
}

// -------------------------------------------------------------

private Integer logLevel() {
  //
  // Get the log level as an Integer:
  //
  //   0) log only Errors
  //   1) log Errors and Warnings
  //   2) log Errors, Warnings and Info
  //   3) log Errors, Warnings, Info and Debug
  //   4) log Errors, Warnings, Info, Debug and Trace (everything)
  //
  // If the level is not yet set in the driver preferences, return a default of 2 (Info)
  //
  if (settings.logLevel != null) return (settings.logLevel.toInteger());
  return (2);
}

// -------------------------------------------------------------

private Boolean isDeviceInitialized() {
  return (state.volume? true: false);
}

// Logging ---------------------------------------------------------------------------------------------------------------------

private void logError(String str) { log.error(str); }
private void logWarning(String str) { if (logLevel() > 0) log.warn(str); }
private void logInfo(String str) { if (logLevel() > 1) log.info(str); }
private void logDebug(String str) { if (logLevel() > 2) log.debug(str); }
private void logTrace(String str) { if (logLevel() > 3) log.trace(str); }

// -------------------------------------------------------------

private void logResponse(String id, Object obj) {
  //
  // Log a generic groovy object
  // Used only for diagnostic/debug purposes
  //
  if (logLevel() > 3) {
    String text = id;
    obj.properties.each {
      text += "\n${it}";
    }
    logTrace(text);
  }
}

// -------------------------------------------------------------

void logDebugOff() {
  //
  // runIn() callback to disable "Debug" logging after 30 minutes
  // Cannot be private
  //
  if (logLevel() > 2) device.updateSetting("logLevel", [type: "enum", value: "2"]);
}

// Attribute handling ----------------------------------------------------------------------------------------------------------

private Boolean attributeSetString(String attribute, String val) {
  //
  // Only set <attribute> if new <val> is different
  // Return true if <attribute> has actually been updated/created
  //
  if ((device.currentValue(attribute) as String) != val) {
    sendEvent(name: attribute, value: val);
    return (true);
  }

  return (false);
}

// -------------------------------------------------------------

private Boolean attributeSetNumber(String attribute, BigDecimal val, String measure = null) {
  //
  // Only set <attribute> if new <val> is different
  // Return true if <attribute> has actually been updated/created
  //
  if ((device.currentValue(attribute) as BigDecimal) != val) {
    if (measure) sendEvent(name: attribute, value: val, unit: measure);
    else sendEvent(name: attribute, value: val);
    return (true);
  }

  return (false);
}

// Yamaha Commands -------------------------------------------------------------------------------------------------------------

private Map deviceCommand(String address, String command) {
  //
  // Return a Map with the following content:
  //   error: 'error' message
  // success: JSON data returned by the AVR
  //
  Map data = [:];
  String uri = "http://${address}/YamahaExtendedControl/v1/${command}";

  try {
    httpGet(uri) { /* groovyx.net.http.HttpResponseDecorator */ resp ->
      // If trace is enabled, log the response received from the device
      logResponse("deviceCommand(${uri})", resp);

      if ((resp.status as Integer) != 200) throw new Exception("httpGet returned code ${resp.status}");

      data = resp.data as Map;
      if (data.response_code) throw new Exception("avr returned code ${data.response_code}");
    }
  }
  catch (Exception e) {
    data.error = "deviceCommand(${uri}): ${e.getMessage()}";
  }

  return (data);
}

// -------------------------------------------------------------

private Map systemInfo(String address) {
  //
  // Retrieve system and zones info
  // Return a Map with the following content:
  //   error: 'error' message
  // success: system info and features returned by the AVR
  //
  Map data = [:];

  //
  // Retrieve system info
  //
  String command = "system/getDeviceInfo";
  Map device = deviceCommand(address, command);
  if (device.error) data.error = device.error;
  else {
    data.model = device.model_name;
    data.firmware = device.system_version;
    data.mac = device.device_id;

    //
    // Retrieve zone info
    //
    command = "system/getFeatures";
    device = deviceCommand(address, command);
    if (device.error) data.error = device.error;
    else {
      data.zone_list = [:];
      device.zone.each {
        Map volume = it.range_step.find { it.id == "volume" } as Map;
        if (volume && it.input_list) {
          Map zone = [:];
          zone.input_list = it.input_list;
          zone.volume = [:];
          zone.volume.min = volume.min;
          zone.volume.max = volume.max;
          zone.volume.step = volume.step;

          data.zone_list."${it.id}" = zone;
        }
      }
    }
  }

  return (data);
}

// -------------------------------------------------------------

private Map zoneInfo(String address, String zone) {
  //
  // Retrieve zone status
  // Return a Map with the following content:
  //   error: 'error' message
  // success: zone info returned by the AVR
  //
  Map data = [:];
  String command = "${zone}/getStatus";

  Map device = deviceCommand(address, command);
  if (device.error) data.error = device.error;
  else {
    data.power = device.power;
    data.volume = device.volume;
    data.mute = device.mute;
    data.input = device.input;
    if (device.actual_volume?.mode == "db") data.decibels = device.actual_volume.value;
  }

  return (data);
}

// -------------------------------------------------------------

private String zonePower(String address, String zone, String power) {
  //
  // Set zone power state
  // <power> must be "on", "standby" or "toggle"
  // Return an error message or null in case of success
  //
  String command = "${zone}/setPower?power=${power}";

  return (deviceCommand(address, command).error);
}

// -------------------------------------------------------------

private String zoneVolume(String address, String zone, String volume, String step = null) {
  //
  // Set zone volume
  // <volume> must be a number, "up" or "down"
  // <step> must be a number in case <volume> is  "up" or "down"
  // Return an error message or null in case of success
  //
  String command = "${zone}/setVolume?volume=${volume}";
  if (volume == "up" || volume == "down") command += "&step=${step}";

  return (deviceCommand(address, command).error);
}

// -------------------------------------------------------------

private String zoneMute(String address, String zone, String mute) {
  //
  // Set zone mute state
  // <mute> must be "true" or "false"
  // Return an error message or null in case of success
  //
  String command = "${zone}/setMute?enable=${mute}";

  return (deviceCommand(address, command).error);
}

// -------------------------------------------------------------

private String zoneInput(String address, String zone, String input) {
  //
  // Set zone input
  // Return an error message or null in case of success
  //
  String command = "${zone}/setInput?input=${input}";

  return (deviceCommand(address, command).error);
}

// Helpers ---------------------------------------------------------------------------------------------------------------------

private BigDecimal convertVolume(BigDecimal val, BigDecimal inMin, BigDecimal inMax, BigDecimal outMin, BigDecimal outMax, BigDecimal outStep = 1) {
  // Restrain input value
  if (val < inMin) val = inMin;
  else if (val > inMax) val = inMax;

  // Convert to new range
  val = ((val - inMin) * (outMax - outMin)) / (inMax - inMin) + outMin;

  // Force outStep number of decimals
  Integer decimals = Math.max(0, outStep.stripTrailingZeros().scale());
  
  // We use the Float round because the BigDecimal one is not supported/not working on Hubitat
  val = val.toFloat().round(decimals).toBigDecimal();

  BigDecimal integer = val.toBigInteger();

  // We don't strip zeros on an integer otherwise it gets converted to scientific exponential notation
  val = (val == integer)? integer: val.stripTrailingZeros();

  // Ensure final value is multiple of outStep
  val -= val.remainder(outStep);

  return (val);
}

// Driver Commands -------------------------------------------------------------------------------------------------------------

void on(Boolean off = false) {
  String func = off? "off()": "on()";

  try {
    if (!isDeviceInitialized()) throw new Exception("device not initialized");

    String error = zonePower(deviceAddress(), deviceZone(), off? "standby": "on");
    if (error) throw new Exception(error);

    logInfo(func);
    runIn(2, refresh);
  }
  catch (Exception e) {
    logError("${func}: ${e.getMessage()}");
  }
}

// -------------------------------------------------------------

void off() { on(true); }

// -------------------------------------------------------------

void mute(Boolean unmute = false) {
  String func = unmute? "unmute()": "mute()";

  try {
    if (!isDeviceInitialized()) throw new Exception("device not initialized");

    String error = zoneMute(deviceAddress(), deviceZone(), unmute? "false": "true");
    if (error) throw new Exception(error);

    logInfo(func);
    runIn(2, refresh);
  }
  catch (Exception e) {
    logError("${func}: ${e.getMessage()}");
  }
}

// -------------------------------------------------------------

void unmute() { mute(true); }

// -------------------------------------------------------------

void setVolume(BigDecimal val) {
  String func, vol, step;

  if (val < 0) {
    func = "volumeDown()";
    vol = "down";
    step = convertVolume(volumeStep(), 0, 100, state.volume.min, state.volume.max, state.volume.step).toString();
  }
  else if (val > 100) {
    func = "volumeUp()";
    vol = "up";    
    step = convertVolume(volumeStep(), 0, 100, state.volume.min, state.volume.max, state.volume.step).toString();     
  }
  else {
    func = "setVolume(${val})";
    vol = convertVolume(val, 0, 100, state.volume.min, state.volume.max, state.volume.step).toString();
    step = null;
  }

  try {
    if (!isDeviceInitialized()) throw new Exception("device not initialized");

    String error = zoneVolume(deviceAddress(), deviceZone(), vol, step);
    if (error) throw new Exception(error);

    logInfo(func);
    runIn(2, refresh);
  }
  catch (Exception e) {
    logError("${func}: ${e.getMessage()}");
  }
}

// -------------------------------------------------------------

void volumeUp() { setVolume(101); }

// -------------------------------------------------------------

void volumeDown() { setVolume(-1); }

// -------------------------------------------------------------

void setInputSource(String name) {
  String func = "setInputSource(${name})";

  try {
    if (!isDeviceInitialized()) throw new Exception("device not initialized");

    String error = zoneInput(deviceAddress(), deviceZone(), name);
    if (error) throw new Exception(error);

    logInfo(func);
    runIn(2, refresh);
  }
  catch (Exception e) {
    logError("${func}: ${e.getMessage()}");
  }
}

// -------------------------------------------------------------

void refresh(Boolean poll = false) {
  String func = poll? "poll()": "refresh()";

  try {
    if (!isDeviceInitialized()) throw new Exception("device not initialized");

    Map info = zoneInfo(deviceAddress(), deviceZone());
    if (info.error) throw new Exception(info.error);

    attributeSetString("switch", (info.power == "on")? "on": "off");
    attributeSetNumber("volume", convertVolume(info.volume, state.volume.min, state.volume.max, 0, 100, 1), "%");
    attributeSetString("mute", info.mute? "muted": "unmuted");
    attributeSetString("mediaInputSource", info.input);
    if (info.decibels) attributeSetNumber("decibels", info.decibels, "dB");

    poll? logDebug(func): logInfo(func);    
  }
  catch (Exception e) {
    logError("${func}: ${e.getMessage()}");
  }
}

// -------------------------------------------------------------

void poll() { refresh(true); }

// Driver lifecycle ------------------------------------------------------------------------------------------------------------

void installed() {
  //
  // Called once when the driver is created
  //
  logDebug("installed()");
}

// -------------------------------------------------------------

void updated() {
  //
  // Called everytime the user saves the driver preferences
  //
  try {
    // Clear previous states
    state.clear();    

    // Unschedule possible previous runIn() calls
    unschedule();

    // Turn off debug log in 30 minutes
    if (logLevel() > 2) runIn(1800, logDebugOff);

    // Get device info
    Map info = systemInfo(deviceAddress());
    if (info.error) throw new Exception(info.error);

    String inputs = "[]";
    ArrayList<String> zones = [];
    Map volume = [:];

    info.zone_list.each {
      zones.add(it.key);
      if (it.key == deviceZone()) {
        volume = it.value.volume;
        // inputs = groovy.json.JsonOutput.toJson(it.value.input_list);
        inputs = it.value.input_list.toString();        
      }
    }

    // Save zone list for the user to see
    state.zones = zones;

    // Routine sanity checks
    Integer volStep = volumeStep();
    if (volStep < 1 || volStep > 10) throw new Exception("${volStep}% is an invalid volume step");
    Integer pollMin = devicePolling();
    if (pollMin < 0 || pollMin > 59) throw new Exception("${pollMin} minutes is an invalid polling interval");
    if (!zones.contains(deviceZone())) throw new Exception("${deviceZone()} is an invalid zone");
    if (!volume) throw new Exception("missing zone volume range");

    // Setup polling (if requested) and 
    if (pollMin) schedule("0 */${pollMin} * ? * *", poll);

    // Save current zone volume range: this will officially mark the device as successfully initialized 
    state.volume = volume;

    // Update all attributes
    attributeSetString("model", info.model);
    attributeSetNumber("firmware", info.firmware);
    attributeSetString("supportedInputs", inputs);    

    logDebug("updated()");
    runIn(2, refresh);
  }
  catch (Exception e) {
    // Display to the user that the device as not been successfully initialized
    state.initialize = "<font style='color:red'>${e.getMessage()}</font>";

    logError("updated(): ${e.getMessage()}");    
  }
}

// -------------------------------------------------------------

void uninstalled() {
  //
  // Called once when the driver is deleted
  //
  logDebug("uninstalled()");
}

// -------------------------------------------------------------

void parse(String msg) {
  //
  // Called everytime a event is received
  //
  logDebug("parse(): ${msg}"); 
}

// Recycle Bin -----------------------------------------------------------------------------------------------------------------

/*

  Map params = [
    uri: "http://${address}/YamahaExtendedControl/v1/${command}",
    headers: [
      "X-AppName": "MusicCast/1.0(hubitat)",
      "X-AppPort": "41100"
    ]
  ]

  httpGet(params)

*/

// EOF -------------------------------------------------------------------------------------------------------------------------
