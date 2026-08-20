/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.remotecontrol

import java.util.UUID


/**
 * This class includes the GATT attributes of the Kraken Smart Housing, which is
 * an underwater camera housing that communicates its key presses with the phone over
 * Bluetooth Low Energy
 */
internal object KrakenGattAttributes {
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    //static final UUID KRAKEN_SENSORS_SERVICE = UUID.fromString("00001623-1212-efde-1523-785feabcd123");
    val KRAKEN_SENSORS_CHARACTERISTIC: UUID =
        UUID.fromString("00001625-1212-efde-1523-785feabcd123")

    //static final UUID KRAKEN_BUTTONS_SERVICE= UUID.fromString("00001523-1212-efde-1523-785feabcd123");
    val KRAKEN_BUTTONS_CHARACTERISTIC: UUID =
        UUID.fromString("00001524-1212-efde-1523-785feabcd123")

    val desiredCharacteristics: List<UUID>
        //static final UUID BATTERY_SERVICE = UUID.fromString("180f");
        get() = listOf(KRAKEN_BUTTONS_CHARACTERISTIC, KRAKEN_SENSORS_CHARACTERISTIC)
}
