package dev.mike.fieronav

import dev.mike.fieronav.ui.Mercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MercatorTest {

    @Test
    fun worldEdgesAndCenter() {
        assertEquals(0.0, Mercator.x(-180.0, 0), 1e-9)
        assertEquals(128.0, Mercator.x(0.0, 0), 1e-9)
        assertEquals(128.0, Mercator.y(0.0, 0), 1e-9)
        assertEquals(512.0 * 1024, Mercator.size(11), 1e-9)
    }

    @Test
    fun matchesTheStandardTileNumbering() {
        // San Francisco Ferry Building at zoom 12 is tile x=655, y=1582 (OSM slippy-map numbering).
        val z = 12
        assertEquals(655, (Mercator.x(-122.3937, z) / 256).toInt())
        assertEquals(1582, (Mercator.y(37.7955, z) / 256).toInt())
    }

    @Test
    fun inverseRoundTrips() {
        val z = 15
        assertEquals(37.7955, Mercator.lat(Mercator.y(37.7955, z), z), 1e-9)
        assertEquals(-122.3937, Mercator.lon(Mercator.x(-122.3937, z), z), 1e-9)
    }

    @Test
    fun fitZoomShowsTheWholeRoute() {
        // Ferry Building -> Golden Gate Bridge (~7 km) on a 240x240 view with 18 px padding.
        val pts = listOf(LatLng(37.7955, -122.3937), LatLng(37.8078, -122.4750))
        val z = Mercator.fitZoom(pts, 240, 240, 18)
        val w = Mercator.x(-122.3937, z) - Mercator.x(-122.4750, z)
        assertTrue("fits at $z", w <= 240 - 36)
        val wNext = Mercator.x(-122.3937, z + 1) - Mercator.x(-122.4750, z + 1)
        assertTrue("one level closer would not fit", wNext > 240 - 36)
        assertEquals(17, Mercator.fitZoom(listOf(LatLng(30.0, -97.0)), 240, 240, 18))
    }
}
