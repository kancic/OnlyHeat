package ancic.karim.onlyheat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiTest {
    // Trimmed real /getRooms reply (2026-11-21).
    @Test fun parsesRealRooms() {
        val json = """{"status":"OK","globalHeatingCooling":"heating","globalDeviceName":"Home","rooms":[
            {"id":"1","title":"Kupaona","isHeating":"heating","isSmart":false,"isSchedule":true,"isWorking":false,
             "currentTemp":"24.687","currentHum":null,"targetTemp":"22"},
            {"id":"2","title":"Dnevni","isHeating":"heating","isSmart":false,"isSchedule":false,"isWorking":true,
             "currentTemp":"19.5","currentHum":"41","targetTemp":""}]}"""
        val h = parseHome(Json.parseToJsonElement(json).jsonObject)
        assertEquals(Room("1", "Kupaona", 24.687, 22.0, null, heating = false, smart = false, schedule = true), h.rooms[0])
        assertEquals(Room("2", "Dnevni", 19.5, null, 41.0, heating = true, smart = false, schedule = false), h.rooms[1])
    }

    @Test fun formatsLikeTheOriginalApp() {
        assertEquals("21", fmt(21.0)); assertEquals("21.5", fmt(21.5)); assertEquals("", fmt(null))
        assertEquals("-0.5", fmt1(-0.5)); assertEquals("24.7", fmt1(24.687))
    }

    @Test fun daySeparatesHistoryFromPrediction() {
        val d = parseDay(Json.parseToJsonElement("""{"roomID":"3","roomName":"Spavaca soba","logs":[
            {"dateTime":1795176000,"currentTemp":"23.875","targetTemp":"23"},
            {"dateTime":1795266000,"predictedCurrentTemp":"23.412","predictedTargetTemp":"20.2"}]}""").jsonObject)
        assertEquals(listOf(Point(1795176000, 23.875, 23.0)), d.points)
        assertEquals(listOf(Point(1795266000, 23.412, 20.2)), d.forecast)
    }

    // Trimmed real /getSmartRoomLogsLast24h logs on firmware 1.0.9.2.0 (2026-11-23).
    @Test fun dayReadsOutsideTemp() {
        val d = parseDay(Json.parseToJsonElement("""{"roomID":"3","logs":[
            {"dateTime":1795366800,"currentTemp":"23.562","targetTemp":"23.2","outsideTemp":"5"},
            {"dateTime":1795453200,"predictedCurrentTemp":"23.325","predictedTargetTemp":"23.2","predictedOutsideTemp":"-1.5"}]}""").jsonObject)
        assertEquals(5.0, d.points[0].outside)
        assertEquals(-1.5, d.forecast[0].outside)
        // Firmware 1.0.7.0.8 without AccuWeather sends 0 everywhere: that's no data, not 0°.
        val old = parseDay(Json.parseToJsonElement("""{"roomID":"3","logs":[
            {"dateTime":1795366800,"currentTemp":"23.5","targetTemp":"23","outsideTemp":"0"},
            {"dateTime":1795453200,"predictedCurrentTemp":"23.5","predictedTargetTemp":"23","predictedOutsideTemp":"0"}]}""").jsonObject)
        assertEquals(listOf(null, null), (old.points + old.forecast).map { it.outside })
    }

    @Test fun dayCanContainOnlyAnOutdoorPrediction() {
        val day = parseDay(Json.parseToJsonElement("""{"logs":[{"dateTime":1795453200,"predictedOutsideTemp":"-1.5"}]}""").jsonObject)
        assertEquals(emptyList(), day.points)
        assertEquals(listOf(Point(1795453200, -1.5, null)), day.forecast.outside())
    }

    // Trimmed real /getSmartForecast reply (2026-11-23).
    @Test fun weatherFromSmartForecast() {
        val json = """{"status":"OK","location":"Zagreb; Zagreb City; Croatia","dasboardForecast":[
            {"id":"daily","title":"Current Conditions","headline":"Mostly clear",
             "image":"http:\/\/vortex.accuweather.com\/adc2010\/images\/slate\/icons\/34","temperature":2.8,"temperatureUnit":"C"},
            {"id":"today","image":"http:\/\/vortex.accuweather.com\/adc2010\/images\/slate\/icons\/04","temperatureMax":7.8}]}"""
        assertEquals(Weather(2.8, "🌙"), parseWeather(Json.parseToJsonElement(json).jsonObject))
        assertEquals(Weather(-0.5, null), parseWeather(Json.parseToJsonElement(
            """{"dasboardForecast":[{"id":"daily","image":"…/icons/0","temperature":-0.5}]}""").jsonObject))
        assertEquals(Weather(null, null), parseWeather(Json.parseToJsonElement("""{"status":"OK"}""").jsonObject))
    }

    @Test fun revokedKeyLogsOut() {
        assertIs<AuthExpired>(apiError("Invalid authentication key")) // key missing
        assertIs<AuthExpired>(apiError("No device found for requested authentication key")) // API PIN regenerated
        assertIs<ApiException>(apiError("command disabled")).also { assertEquals("command disabled", it.message) }
    }
}
