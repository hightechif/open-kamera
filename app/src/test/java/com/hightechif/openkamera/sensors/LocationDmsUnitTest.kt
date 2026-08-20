/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationDmsUnitTest {

    @Test
    fun testLocationToDMS() {
        assertEquals("0°0'0\"", LocationSupplier.locationToDMS(0.0))
        assertEquals("0°0'0\"", LocationSupplier.locationToDMS(0.0000306))
        assertEquals("0°0'1\"", LocationSupplier.locationToDMS(0.000306))
        assertEquals("0°0'11\"", LocationSupplier.locationToDMS(0.00306))
        assertEquals("0°59'59\"", LocationSupplier.locationToDMS(0.9999))
        assertEquals("1°44'37\"", LocationSupplier.locationToDMS(1.7438))
        assertEquals("53°0'0\"", LocationSupplier.locationToDMS(53.000137))
        assertEquals("147°0'33\"", LocationSupplier.locationToDMS(147.00938))
        assertEquals("0°0'0\"", LocationSupplier.locationToDMS(-0.0))
        assertEquals("0°0'0\"", LocationSupplier.locationToDMS(-0.0000306))
        assertEquals("-0°0'1\"", LocationSupplier.locationToDMS(-0.000306))
        assertEquals("-0°0'11\"", LocationSupplier.locationToDMS(-0.00306))
        assertEquals("-0°59'59\"", LocationSupplier.locationToDMS(-0.9999))
        assertEquals("-1°44'37\"", LocationSupplier.locationToDMS(-1.7438))
        assertEquals("-53°0'0\"", LocationSupplier.locationToDMS(-53.000137))
        assertEquals("-147°0'33\"", LocationSupplier.locationToDMS(-147.00938))
    }
}
