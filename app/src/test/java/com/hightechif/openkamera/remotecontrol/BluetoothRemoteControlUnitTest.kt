package com.hightechif.openkamera.remotecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.roundToInt

class BluetoothRemoteControlUnitTest {

    @Test
    fun testKrakenGattUuids() {
        val buttonsUuid = KrakenGattAttributes.KRAKEN_BUTTONS_CHARACTERISTIC
        val sensorsUuid = KrakenGattAttributes.KRAKEN_SENSORS_CHARACTERISTIC
        val configUuid = KrakenGattAttributes.CLIENT_CHARACTERISTIC_CONFIG

        assertEquals(UUID.fromString("00001524-1212-efde-1523-785feabcd123"), buttonsUuid)
        assertEquals(UUID.fromString("00001625-1212-efde-1523-785feabcd123"), sensorsUuid)
        assertEquals(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), configUuid)

        val desired = KrakenGattAttributes.desiredCharacteristics
        assertEquals(2, desired.size)
        assertTrue(desired.contains(buttonsUuid))
        assertTrue(desired.contains(sensorsUuid))
    }

    @Test
    fun testKrakenButtonCodeMapping() {
        fun decodeKrakenButtonCode(buttonCode: Int): Int {
            return when (buttonCode) {
                32 -> BluetoothLeService.COMMAND_SHUTTER
                16 -> BluetoothLeService.COMMAND_MODE
                48 -> BluetoothLeService.COMMAND_MENU
                97 -> BluetoothLeService.COMMAND_AFMF
                64 -> BluetoothLeService.COMMAND_UP
                80 -> BluetoothLeService.COMMAND_DOWN
                else -> -1
            }
        }

        assertEquals(BluetoothLeService.COMMAND_SHUTTER, decodeKrakenButtonCode(32))
        assertEquals(BluetoothLeService.COMMAND_MODE, decodeKrakenButtonCode(16))
        assertEquals(BluetoothLeService.COMMAND_MENU, decodeKrakenButtonCode(48))
        assertEquals(BluetoothLeService.COMMAND_AFMF, decodeKrakenButtonCode(97))
        assertEquals(BluetoothLeService.COMMAND_UP, decodeKrakenButtonCode(64))
        assertEquals(BluetoothLeService.COMMAND_DOWN, decodeKrakenButtonCode(80))
        assertEquals(-1, decodeKrakenButtonCode(999))
    }

    @Test
    fun testKrakenSensorPayloadDecoding() {
        // Kraken returns 4 bytes:
        // Byte 0-1: depth raw
        // Byte 2-3: temp raw
        val byte0 = 0x50 // 80
        val byte1 = 0x01 // 1 -> raw depth = 80 + 256 = 336 -> 33.6m
        val byte2 = 0x0E // 14
        val byte3 = 0x01 // 1 -> raw temp = 14 + 256 = 270 -> 27.0 deg C

        val rawDepth = byte0 + (byte1 shl 8)
        val rawTemp = byte2 + (byte3 shl 8)

        val depthFreshWater = rawDepth / 10.0
        val temp = rawTemp / 10.0
        val saltwaterDensity = 1.03
        val depthSaltWater = ((depthFreshWater / saltwaterDensity) * 10.0).roundToInt() / 10.0

        assertEquals(33.6, depthFreshWater, 0.001)
        assertEquals(27.0, temp, 0.001)
        assertEquals(32.6, depthSaltWater, 0.001)
    }
}
