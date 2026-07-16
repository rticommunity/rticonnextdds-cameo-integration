/*
 * (c) Copyright, Real-Time Innovations, 2026.  All rights reserved.
 * RTI grants Licensee a license to use, modify, compile, and create derivative
 * works of the software solely for use with RTI Connext DDS. Licensee may
 * redistribute copies of the software provided that all such copies are subject
 * to this license. The software is provided "as is", with no warranty of any
 * type, including any warranty for fitness for any purpose. RTI is under no
 * obligation to maintain or support the software. RTI shall not be liable for
 * any incidental or consequential damages arising out of the use or inability
 * to use the software.
 */

// CAMEO Opaque Behavior — Groovy
// Sends a ShapeType sample to RTI Web Integration Service (WIS) via REST.
//
// WIS must already be running with wis_config.xml loaded:
//   rtiwebintegrationservice -cfgFile wis_config.xml -cfgName ShapeWIS
//
// The script receives a JSON string describing the ShapeType sample and POSTs
// it to the WIS REST endpoint for the configured data writer.
//
// How to wire in CAMEO:
//   1. Create an Opaque Behavior on a Block or Activity.
//   2. Set Language = "Groovy".
//   3. Paste this script as the body.
//   4. Create a String-typed Parameter named "json_input" on the behavior.
//      CAMEO will bind it to the local variable of the same name at runtime.
//      Alternatively, hard-code or construct json_input in the section below.
//
// Can also be invoked from another Groovy script (e.g. WISWriterTest.groovy)
// by evaluating this file through a GroovyShell with a pre-populated binding
// that contains "json_input" — matching exactly how CAMEO injects the parameter.
//
// Example jsonBody values:
//   Square  : {"color":"BLUE","x":100,"y":200,"shapesize":30}
//   Circle  : {"color":"RED","x":50,"y":50,"shapesize":40}
//   Triangle: {"color":"GREEN","x":150,"y":100,"shapesize":25}

// ── Configuration ────────────────────────────────────────────────────────────
// Each variable is read from the Binding first (CAMEO parameter injection or
// test harness override), then falls back to the default value.  This means
// a GroovyShell caller can pre-set e.g. DW_NAME = "CircleWriter" in the
// binding and it will take precedence over the default below.
String WIS_HOST   = binding.hasVariable("WIS_HOST")   ? binding.WIS_HOST   : "localhost"
int    WIS_PORT   = binding.hasVariable("WIS_PORT")   ? binding.WIS_PORT   : 8080
String APP_NAME   = binding.hasVariable("APP_NAME")   ? binding.APP_NAME   : "ShapeApplication"
String DP_NAME    = binding.hasVariable("DP_NAME")    ? binding.DP_NAME    : "ShapeParticipant"
String PUB_NAME   = binding.hasVariable("PUB_NAME")   ? binding.PUB_NAME   : "Publisher"
String DW_NAME    = binding.hasVariable("DW_NAME")    ? binding.DW_NAME    : "SquareWriter"
int    TIMEOUT_MS = binding.hasVariable("TIMEOUT_MS") ? binding.TIMEOUT_MS : 5000

// json_input is the JSON string supplied by CAMEO (or the test binding) as a parameter.
// If running standalone (outside CAMEO/test), assign a test value here:
// String json_input = '{"color":"BLUE","x":100,"y":200,"shapesize":30}'
// ─────────────────────────────────────────────────────────────────────────────

if (json_input == null || json_input.trim().isEmpty()) {
    throw new IllegalArgumentException(
        "json_input parameter is null or empty — provide a JSON ShapeType object")
}

// ── Normalize field names to match DDS ShapeType ─────────────────────────────
// Uses only Java regex — avoids groovy.json which is unavailable in CAMEO's
// embedded script engine classpath.
def normalized = json_input.trim()

// Unwrap {"sample": {...}} envelope if present.
def sampleMatch = normalized =~ /^\{\s*"sample"\s*:\s*(\{.*\})\s*\}$/
if (sampleMatch) normalized = sampleMatch[0][1]

// Remap field aliases to canonical DDS ShapeType member names.
normalized = normalized.replaceAll(/"position_x"\s*:/, '"x":')
normalized = normalized.replaceAll(/"position_y"\s*:/, '"y":')
normalized = normalized.replaceAll(/"shape_size"\s*:/,  '"shapesize":')
if (!normalized.contains('"shapesize"')) {
    normalized = normalized.replaceAll(/"size"\s*:/, '"shapesize":')
}

// Strip all fields except the four valid ShapeType members.
// CAMEO may inject extra block attributes (e.g. participant_qualified_name)
// that WIS rejects with HTTP 422.
def extract = { String json, String key ->
    def m = json =~ /"${key}"\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?)/
    m ? m[0][1] : null
}
def color     = extract(normalized, "color")
def xVal      = extract(normalized, "x")
def yVal      = extract(normalized, "y")
def sizeVal   = extract(normalized, "shapesize")

if (!color || xVal == null || yVal == null || sizeVal == null) {
    throw new IllegalArgumentException(
        "json_input is missing required ShapeType fields (color, x, y, shapesize). Got: ${normalized}")
}
json_input = """{"color":${color},"x":${xVal},"y":${yVal},"shapesize":${sizeVal}}"""
// ─────────────────────────────────────────────────────────────────────────────

// ── sendToWIS closure ────────────────────────────────────────────────────────
// Exposed so that scripts evaluating this file via GroovyShell can call it
// directly (e.g. to publish to a different writer) without re-evaluating.
// CAMEO does not use this closure — it just runs the script top-to-bottom.
// ─────────────────────────────────────────────────────────────────────────────
sendToWIS = { String host, int port, String app, String dp, String pub, String dw,
              String payload, int timeoutMs ->
    String url = String.format(
        "http://%s:%d/dds/rest1/applications/%s/domain_participants/%s/publishers/%s/data_writers/%s",
        host, port, app, dp, pub, dw)

    println "WIS endpoint : ${url}"
    println "Sending JSON : ${payload}"

    def conn = (java.net.HttpURLConnection) new URL(url).openConnection()
    try {
        conn.setRequestMethod("POST")
        conn.setDoOutput(true)
        conn.setConnectTimeout(timeoutMs)
        conn.setReadTimeout(timeoutMs)
        conn.setRequestProperty("Content-Type", "application/dds-web+json")
        conn.setRequestProperty("Accept",       "application/dds-web+json")
        byte[] body = payload.getBytes("UTF-8")
        conn.setRequestProperty("Content-Length", String.valueOf(body.length))
        conn.outputStream.write(body)
        conn.outputStream.flush()

        int statusCode = conn.responseCode
        String responseBody = ""
        try {
            def stream = (statusCode >= 200 && statusCode < 300)
                ? conn.inputStream : conn.errorStream
            if (stream != null) responseBody = stream.getText("UTF-8")
        } catch (Exception ignored) {}

        return [status: statusCode, body: responseBody]
    } finally {
        conn.disconnect()
    }
}

// ── Main: publish the sample received from the CAMEO binding ─────────────────
def result = sendToWIS(WIS_HOST, WIS_PORT, APP_NAME, DP_NAME, PUB_NAME, DW_NAME,
                       json_input, TIMEOUT_MS)

if (result.status >= 200 && result.status < 300) {
    println "SUCCESS — HTTP ${result.status}"
    if (result.body) println "Response: ${result.body}"
} else {
    println "ERROR — HTTP ${result.status}: ${result.body}"
    throw new RuntimeException(
        "WIS REST call failed with HTTP ${result.status}: ${result.body}")
}
