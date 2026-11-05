package dev.mike.fieronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLogicTest {

    // ---- Geo ----

    @Test
    fun decodesGooglesReferencePolyline() {
        // Example from Google's encoded-polyline documentation (precision 1e5).
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 1e5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-9)
        assertEquals(-120.2, pts[0].lon, 1e-9)
        assertEquals(40.7, pts[1].lat, 1e-9)
        assertEquals(-120.95, pts[1].lon, 1e-9)
        assertEquals(43.252, pts[2].lat, 1e-9)
        assertEquals(-126.453, pts[2].lon, 1e-9)
    }

    @Test
    fun distanceOfOneDegreeLatitudeIsAbout111Km() {
        assertEquals(111_195.0, Geo.distance(LatLng(30.0, -97.0), LatLng(31.0, -97.0)), 50.0)
    }

    @Test
    fun projectionFindsFractionAndOffset() {
        val a = LatLng(30.0, -97.0)
        val b = LatLng(30.0, -96.99) // ~963 m east
        val p = LatLng(30.0009, -96.995) // ~100 m north of the midpoint
        val proj = Geo.project(p, a, b)
        assertEquals(0.5, proj.fraction, 0.01)
        assertEquals(100.0, proj.distance, 2.0)
    }

    @Test
    fun bearingEastIs90() {
        assertEquals(90.0, Geo.bearing(LatLng(30.0, -97.0), LatLng(30.0, -96.9)), 0.1)
    }

    // ---- Units ----

    @Test
    fun shortDistancesInFeetLongerInMiles() {
        assertEquals("400 ft", Units.distance(122.0))
        assertEquals("30 ft", Units.distance(9.0))
        assertEquals("0.4 mi", Units.distance(644.0))
        assertEquals("16 mi", Units.distance(25_750.0))
    }

    @Test
    fun spokenDistancesUseFriendlyFractions() {
        assertEquals("500 feet", Units.spokenDistance(152.0))
        assertEquals("a quarter mile", Units.spokenDistance(400.0))
        assertEquals("half a mile", Units.spokenDistance(800.0))
        assertEquals("1 mile", Units.spokenDistance(1609.0))
        assertEquals("1.5 miles", Units.spokenDistance(2414.0))
        assertEquals("2 miles", Units.spokenDistance(3219.0))
    }

    @Test
    fun durations() {
        assertEquals("1 min", Units.duration(10.0))
        assertEquals("24 min", Units.duration(1440.0))
        assertEquals("1 h 5 min", Units.duration(3900.0))
    }

    // ---- Instructions ----

    @Test
    fun turnsAndContinues() {
        assertEquals("Turn left onto Main Street", Instructions.text("turn", "left", "Main Street"))
        assertEquals("Slight right onto Oak Lane", Instructions.text("turn", "slight right", "Oak Lane"))
        assertEquals("Sharp left onto Pine Road", Instructions.text("turn", "sharp left", "Pine Road"))
        assertEquals("Make a U-turn onto Main Street", Instructions.text("turn", "uturn", "Main Street"))
        assertEquals("Continue onto US 290", Instructions.text("new name", "straight", "US 290"))
        assertEquals("Bear left onto FM 973", Instructions.text("continue", "slight left", "FM 973"))
    }

    @Test
    fun departAndArrive() {
        assertEquals("Head north on 5th Street", Instructions.text("depart", "", "5th Street", bearingAfter = 2.0))
        assertEquals("Head west", Instructions.text("depart", "", "", bearingAfter = 268.0))
        assertEquals("Arrive at your destination on the left", Instructions.text("arrive", "left", ""))
        assertEquals("Arrive at your destination", Instructions.text("arrive", "", ""))
    }

    @Test
    fun highwayManeuvers() {
        assertEquals(
            "Take the ramp on the right to I 35 toward Waco",
            Instructions.text("on ramp", "slight right", "I 35", destinations = "I 35 North: Waco, Dallas"),
        )
        assertEquals(
            "Take exit 235 A on the right toward 15th Street",
            Instructions.text("off ramp", "slight right", "", exits = "235 A", destinations = "15th Street"),
        )
        assertEquals("Take the exit on the right", Instructions.text("off ramp", "right", ""))
        assertEquals("Keep left at the fork toward 32nd Street", Instructions.text("fork", "slight left", "", destinations = "32nd Street"))
        assertEquals("Merge left onto Purple Heart Trail", Instructions.text("merge", "slight left", "Purple Heart Trail"))
    }

    @Test
    fun roundabouts() {
        assertEquals("At the roundabout, take the second exit onto Elm Street", Instructions.text("roundabout", "right", "Elm Street", exitNumber = 2))
        assertEquals("Exit the roundabout onto Elm Street", Instructions.text("exit roundabout", "right", "Elm Street"))
    }

    @Test
    fun arrowsAndQuietSteps() {
        assertEquals(Arrow.LEFT, Instructions.arrow("turn", "left"))
        assertEquals(Arrow.SLIGHT_RIGHT, Instructions.arrow("off ramp", "slight right"))
        assertEquals(Arrow.ARRIVE, Instructions.arrow("arrive", "left"))
        assertEquals(Arrow.ROUNDABOUT, Instructions.arrow("roundabout", "right"))
        assertTrue(Instructions.isQuiet("new name", "straight"))
        assertTrue(!Instructions.isQuiet("new name", "slight left"))
        assertTrue(!Instructions.isQuiet("turn", "straight"))
    }

    @Test
    fun roadNameFallsBackToRef() {
        assertEquals("Main Street", Routing.roadName("Main Street", "FM 1"))
        assertEquals("US 290", Routing.roadName("", "US 290;TX 71"))
        assertEquals("", Routing.roadName("", ""))
    }

    // ---- search ordering ----

    @Test
    fun nearestBranchFirstButNamesKeepTheirOrder() {
        val me = LatLng(30.0, -97.0)
        fun place(name: String, dLat: Double) = Place(name, "", LatLng(30.0 + dLat, -97.0))
        val photon = listOf(place("H-E-B", 0.2), place("HEB Pharmacy", 0.01), place("H-E-B", 0.05), place("h-e-b ", 0.1))
        val sorted = PlaceSearch.nearestFirstWithinName(photon, me)
        assertEquals(listOf(0.05, 0.1, 0.2, 0.01), sorted.map { Math.round((it.pos.lat - 30.0) * 100) / 100.0 })
        assertEquals("HEB Pharmacy", sorted.last().name)
    }
}
