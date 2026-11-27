package com.pt2vhf.sondefinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    @Test fun metersStayMeters() = assertEquals(1234.5, Units.alt(1234.5, false), 0.0)
    @Test fun metersToFeet() {
        assertEquals(32808.4, Units.alt(10000.0, true), 0.01)
        assertEquals(3.28084, Units.alt(1.0, true), 1e-9)
    }
    @Test fun celsiusToFahrenheit() {
        assertEquals(32.0, Units.temp(0.0, true), 1e-9)
        assertEquals(212.0, Units.temp(100.0, true), 1e-9)
        assertEquals(-40.0, Units.temp(-40.0, true), 1e-9)
        assertEquals(-56.5, Units.temp(-56.5, false), 0.0)
    }
    @Test fun nanPassesThrough() {
        assertTrue(Units.alt(Double.NaN, true).isNaN())
        assertTrue(Units.temp(Double.NaN, true).isNaN())
    }
}
