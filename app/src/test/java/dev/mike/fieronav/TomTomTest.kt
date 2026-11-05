package dev.mike.fieronav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TomTomTest {

    /** Shaped like a TomTom calculateRoute v1 response (fields as documented). */
    private val sample = """
    {
      "formatVersion": "0.0.12",
      "routes": [{
        "summary": { "lengthInMeters": 2400, "travelTimeInSeconds": 420, "trafficDelayInSeconds": 120 },
        "legs": [{ "points": [
          {"latitude": 30.000, "longitude": -97.000},
          {"latitude": 30.000, "longitude": -96.990},
          {"latitude": 30.010, "longitude": -96.990},
          {"latitude": 30.010, "longitude": -96.980},
          {"latitude": 30.020, "longitude": -96.980}
        ]}],
        "sections": [
          {"startPointIndex": 0, "endPointIndex": 4, "sectionType": "TRAVEL_MODE", "travelMode": "car"},
          {"startPointIndex": 0, "endPointIndex": 1, "sectionType": "SPEED_LIMIT", "maxSpeedLimitInKmh": 48},
          {"startPointIndex": 2, "endPointIndex": 3, "sectionType": "SPEED_LIMIT", "maxSpeedLimitInKmh": 105}
        ],
        "guidance": { "instructions": [
          {"routeOffsetInMeters": 0, "travelTimeInSeconds": 0, "pointIndex": 0, "maneuver": "DEPART",
           "street": "Main Street", "message": "Leave from Main Street"},
          {"routeOffsetInMeters": 960, "travelTimeInSeconds": 100, "pointIndex": 1, "maneuver": "TURN_LEFT",
           "street": "Oak Lane", "turnAngleInDecimalDegrees": -90, "message": "Turn left onto Oak Lane"},
          {"routeOffsetInMeters": 2070, "travelTimeInSeconds": 300, "pointIndex": 3, "maneuver": "TAKE_EXIT",
           "exitNumber": "12B", "signpostText": "Airport", "turnAngleInDecimalDegrees": 20, "message": "Take exit 12B"},
          {"routeOffsetInMeters": 2300, "travelTimeInSeconds": 380, "pointIndex": 3, "maneuver": "TAKE_FERRY",
           "message": "Take the ferry to Port Aransas"},
          {"routeOffsetInMeters": 2400, "travelTimeInSeconds": 420, "pointIndex": 4, "maneuver": "ARRIVE_RIGHT",
           "message": "You have arrived"}
        ]}
      }]
    }
    """.trimIndent()

    @Test
    fun parsesSummaryPointsAndTraffic() {
        val r = TomTom.parse(JSONObject(sample)).single()
        assertEquals(5, r.points.size)
        assertEquals(420.0, r.duration, 0.0)
        assertEquals(2400.0, r.distance, 0.0)
        assertEquals(120.0, r.trafficDelay, 0.0)
        assertEquals("TomTom", r.source)
    }

    @Test
    fun instructionsBecomeStepsWithSharedWording() {
        val steps = TomTom.parse(JSONObject(sample)).single().steps
        assertEquals(
            listOf(
                "Head east on Main Street",
                "Turn left onto Oak Lane",
                "Take exit 12B on the right toward Airport",
                "Take the ferry to Port Aransas", // unknown maneuver: TomTom's own message
                "Arrive at your destination on the right",
            ),
            steps.map { it.text },
        )
        assertEquals(listOf(0, 1, 3, 3, 4), steps.map { it.startIndex })
        assertEquals(Arrow.LEFT, steps[1].arrow)
        assertEquals(Arrow.ARRIVE, steps[4].arrow)
        // Duration = time to the next instruction.
        assertEquals(listOf(100.0, 200.0, 80.0, 40.0, 0.0), steps.map { it.duration })
        // The unknown maneuver must still be announced (not treated as a quiet name change).
        assertTrue(!Instructions.isQuiet(steps[3].type, steps[3].modifier))
    }

    @Test
    fun speedLimitsCoverTheirSections() {
        val r = TomTom.parse(JSONObject(sample)).single()
        assertEquals(48, r.speedLimitAt(0))
        assertEquals(48, r.speedLimitAt(1))
        assertEquals(105, r.speedLimitAt(2))
        assertNull(r.speedLimitAt(4))
        assertEquals(30, Units.limitMph(48))
        assertEquals(65, Units.limitMph(105))
        assertEquals(45, Units.limitMph(72))
    }

    @Test
    fun summaryNamesTheLongestRoads() {
        val r = TomTom.parse(JSONObject(sample)).single()
        assertEquals("Oak Lane, Main Street", r.summary)
    }

    @Test
    fun errorsBecomeRouteErrors() {
        try {
            TomTom.parse(JSONObject("""{"error":{"description":"Invalid API key"}}"""))
            throw AssertionError("expected RouteError")
        } catch (e: RouteError) {
            assertEquals("Invalid API key", e.message)
        }
        try {
            TomTom.parse(JSONObject("""{"code":"Unauthorized","message":"You are missing valid authentication credentials"}"""))
            throw AssertionError("expected RouteError")
        } catch (e: RouteError) {
            assertEquals("You are missing valid authentication credentials", e.message)
        }
    }

    @Test
    fun maneuverMapping() {
        assertEquals("on ramp" to "slight left", TomTom.osrmLike("ENTER_FREEWAY", -15.0))
        assertEquals("off ramp" to "slight right", TomTom.osrmLike("MOTORWAY_EXIT_RIGHT", 0.0))
        assertEquals("fork" to "slight left", TomTom.osrmLike("KEEP_LEFT", -10.0))
        assertEquals("turn" to "uturn", TomTom.osrmLike("MAKE_UTURN", -180.0))
        assertEquals("roundabout" to "right", TomTom.osrmLike("ROUNDABOUT_RIGHT", 90.0))
        assertEquals("unknown" to "", TomTom.osrmLike("SOMETHING_NEW", 0.0))
    }

    @Test
    fun mphFromMetresPerSecond() {
        assertEquals(38, Units.mph(17.0))
        assertEquals(0, Units.mph(0.0))
    }
}
