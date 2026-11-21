package ancic.karim.onlyheat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test fun dayKeepsHistoryAndDropsPrediction() {
        val d = parseDay(Json.parseToJsonElement("""{"roomID":"3","roomName":"Spavaca soba","logs":[
            {"dateTime":1795176000,"currentTemp":"23.875","targetTemp":"23"},
            {"dateTime":1795266000,"predictedCurrentTemp":"23.412","predictedTargetTemp":"20.2"}]}""").jsonObject)
        assertEquals(listOf(Point(1795176000, 23.875, 23.0)), d.points)
    }
}
