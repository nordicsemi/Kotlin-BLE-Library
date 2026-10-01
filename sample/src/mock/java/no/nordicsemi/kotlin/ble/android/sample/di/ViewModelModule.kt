/*
 * Copyright (c) 2024, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.kotlin.ble.android.sample.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.advertiser.android.BluetoothLeAdvertiser
import no.nordicsemi.kotlin.ble.advertiser.android.mock.mock
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.mock.mock
import no.nordicsemi.kotlin.ble.client.mock.ConnectionResult
import no.nordicsemi.kotlin.ble.client.mock.DisconnectionReason
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpec
import no.nordicsemi.kotlin.ble.client.mock.PeripheralSpecEventHandler
import no.nordicsemi.kotlin.ble.client.mock.Proximity
import no.nordicsemi.kotlin.ble.client.mock.ReadResponse
import no.nordicsemi.kotlin.ble.client.mock.ServiceDiscoveryResult
import no.nordicsemi.kotlin.ble.client.mock.WriteResponse
import no.nordicsemi.kotlin.ble.client.mock.internal.MockRemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.AdvertisingDataFlag
import no.nordicsemi.kotlin.ble.core.mock.AdvertisingDataScope
import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.CharacteristicProperty
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.OperationStatus
import no.nordicsemi.kotlin.ble.core.Permission
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.TxPowerLevel
import no.nordicsemi.kotlin.ble.core.and
import no.nordicsemi.kotlin.ble.environment.android.mock.MockAndroidEnvironment
import no.nordicsemi.kotlin.log.Log
import no.nordicsemi.kotlin.log.timber.Timber
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Module
@InstallIn(ViewModelComponent::class)
object ViewModelModule {
    /** Handle of the Button characteristic. */
    private var buttonHandle: Int? = null

    /** Handle of the LED characteristic. */
    private var ledHandle: Int? = null

    /**
     * Implementation of the Blinky peripheral behavior.
     *
     * This is based on the [Peripheral LBS](https://docs.nordicsemi.com/bundle/ncs-latest/page/nrf/samples/bluetooth/peripheral_lbs/README.html)
     * (LED Button Service) from Nordic SDK.
     *
     * The device has one characteristic for the Button (read/notify) and one for the LED (read/write).
     */
    private val blinkyImpl: PeripheralSpecEventHandler = object : PeripheralSpecEventHandler {
        /** Checks whether the byte array represents "ON" state. */
        private fun ByteArray.isOn() = isNotEmpty() && this[0] != 0.toByte()
        /** Converts the Boolean to a byte array. */
        private fun Boolean.toBytes(): ByteArray =
            if (this) byteArrayOf(0x01) else byteArrayOf(0x00)

        /** Current state of the LED. */
        private var isLedOn: Boolean = false
        /**
         * Current state of the Button.
         *
         * Changing the state will simulate a notification being sent to the connected clients.
         */
        private var isButtonPressed = false
            set(value) {
                field = value
                buttonHandle?.let {
                    Timber.i("[Blinky] Simulating Button ${if (value) "clicked" else "released"}")
                    blinky.simulateValueUpdate(it, value.toBytes())
                }
            }
        /**
         * Counts how many times the LED characteristic was written to.
         *
         * Used to simulate a service change after 5 writes with response.
         */
        private var blinkCount = 0

        // Event handlers implementation

        override fun onConnectionRequest(phy: PrimaryPhy): ConnectionResult {
            Timber.i("[Blinky] Connection request received on PHY: $phy")
            return ConnectionResult.Accept
        }

        override fun onConnectionLost(reason: DisconnectionReason) {
            Timber.i("[Blinky] Connection terminated: $reason")
        }

        override fun onReset() {
            Timber.i("[Blinky] --- Booting up ---")
            blinkCount = 0
            isLedOn = false
            isButtonPressed = false
        }

        override fun onServiceDiscoveryRequest(uuids: List<Uuid>): ServiceDiscoveryResult {
            Timber.i("[Blinky] Service discovery requested for UUIDs: $uuids")
            return super.onServiceDiscoveryRequest(uuids)
        }

        override fun onWriteRequest(
            characteristic: MockRemoteCharacteristic,
            value: ByteArray
        ): WriteResponse {
            onWriteCommand(characteristic, value)

            // After 5 writes with response, simulate a service change.
            // Note, that the counter isn't reset, so the change happens only once.
            if (blinkCount++ == 5) {
                Timber.i("[Blinky] Changing services")
                blinky.simulateServiceChange {
                    GenericAccessService()
                    GenericAttributeService()
                    // Add LED Button Service (Blinky)
                    Service(
                        uuid = Uuid.parse("00001523-1212-EFDE-1523-785FEABCD123")
                    ) {
                        buttonHandle = Characteristic(
                            uuid = Uuid.parse("00001524-1212-EFDE-1523-785FEABCD123"),
                            properties = CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
                            permission = Permission.READ,
                        )
                        ledHandle = Characteristic(
                            uuid = Uuid.parse("00001525-1212-EFDE-1523-785FEABCD123"),
                            properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,//_WITHOUT_RESPONSE,
                            permissions = Permission.READ and Permission.WRITE,
                        ) {
                            // CCCD is added automatically
                            CharacteristicUserDescriptionDescriptor("LED 2")
                        }
                    }
                }
                CoroutineScope(Dispatchers.IO).launch {
                    // Request shorter supervision timeout.
                    delay(5.seconds)
                    blinky.simulateConnectionParametersRequest(
                        ConnectionParameters(
                            connectionInterval = 30.milliseconds,
                            latency = 4,
                            supervisionTimeout = 1.seconds,
                        )
                    )
                    // Simulate a reset after a while. The Peripheral should get disconnection
                    // event after 1 second (supervision timeout).
                    delay(2.seconds)
                    blinky.simulateReset()
                }
            }

            // Send a Button notification when LED characteristic is written to.
            isButtonPressed = value.isOn()

            return WriteResponse.Success
        }

        override fun onWriteCommand(characteristic: MockRemoteCharacteristic, value: ByteArray) {
            val on = value.isOn()
            isLedOn = on
            Timber.i("[Blinky] LED ${if (on) "ON" else "OFF"}")
        }

        override fun onReadRequest(characteristic: MockRemoteCharacteristic): ReadResponse =
            when (characteristic.instanceId) {
                buttonHandle -> ReadResponse.Success(isButtonPressed.toBytes())
                ledHandle -> ReadResponse.Success(isLedOn.toBytes())
                else -> ReadResponse.Failure(OperationStatus.ReadNotPermitted)
            }
    }

    /** Definition of the Blinky device. */
    private val blinky = PeripheralSpec
        .simulatePeripheral(
            identifier = "AA:BB:CC:DD:EE:FF",
            proximity = Proximity.FAR
        ) {
            advertising(
                parameters = LegacyAdvertisingSetParameters(
                    connectable = true,
                    interval = 500.milliseconds,
                ),
                isAdvertisingWhenConnected = false,
                delay = 1.seconds,
                // timeout = 10.seconds,
                // maxAdvertisingEvents = 30,
            ) {
                CompleteLocalName("Nordic_LBS")
                ServiceUuid(Uuid.parse("00001523-1212-EFDE-1523-785FEABCD123"))
                IncludeTxPowerLevel()
            }
            advertising(
                parameters = Bluetooth5AdvertisingSetParameters(
                    connectable = true,
                    interval = 1.seconds,
                    primaryPhy = PrimaryPhy.PHY_LE_CODED,
                    secondaryPhy = Phy.PHY_LE_CODED,
                    txPowerLevel = TxPowerLevel.HIGH,
                    includeTxPowerLevel = true,
                ),
                delay = 4.seconds,
                timeout = 10.seconds,
            ) {
                Flags(
                    AdvertisingDataFlag.LE_GENERAL_DISCOVERABLE_MODE,
                    AdvertisingDataFlag.BR_EDR_NOT_SUPPORTED,
                )
                CompleteLocalName("HR Sensor")
                ServiceUuid(shortUuid = 0x1809)
                ServiceUuid(shortUuid = 0x180A)
            }
            connectable(
                name = "Nordic_Blinky",
                maxAttMtu = 247,
                maxL2capMtu = 251,
                // Uncommenting this line switches to a different "connectable" method, which
                // makes the peripheral "cached" (there's additional param "cachedServices" to provide).
                // In that case, the mock impl assumes, that the peripheral was connected before
                // and services were cached, i.e. the Device Name was read. Hence, the scanner
                // will switch from "Nordic_LBS" to "Nordic_Blinky".
                //
                // isBonded = false,
                eventHandler = blinkyImpl,
            ) {
                GenericAccessService()
                GenericAttributeService()
                // Add LED Button Service (Blinky)
                Service(
                    uuid = Uuid.parse("00001523-1212-EFDE-1523-785FEABCD123")
                ) {
                    buttonHandle = Characteristic(
                        uuid = Uuid.parse("00001524-1212-EFDE-1523-785FEABCD123"),
                        properties = CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
                        permission = Permission.READ,
                    ) {
                        // CCCD is added automatically
                        CharacteristicUserDescriptionDescriptor("Button 1", writable = true)
                        // Adding Characteristic Extended Properties Descriptor (CEPD) manually
                        // allows to set Reliable Write property.
                        // The Writable Auxiliaries property is added automatically if CUD is writable.
                        CharacteristicExtendedPropertiesDescriptor(
                            reliableWrite = true,
                            writableAuxiliaries = true
                        )
                        // A custom descriptor with read-only permission. Just for fun.
                        // TODO Reading this should trigger bonding
                        Descriptor(Uuid.random(), permission = Permission.READ_ENCRYPTED)
                    }
                    ledHandle = Characteristic(
                        uuid = Uuid.parse("00001525-1212-EFDE-1523-785FEABCD123"),
                        properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
                        permissions = Permission.READ and Permission.WRITE,
                    ) {
                        // CCCD is added automatically
                        CharacteristicUserDescriptionDescriptor("LED 1")
                    }
                }
            }
        }

    private val beacon = PeripheralSpec.simulatePeripheral(
        identifier = "11:22:33:44:55:66",
        proximity = Proximity.NEAR
    ) {
        advertising(
            parameters = LegacyAdvertisingSetParameters(
                connectable = false,
                interval = 1.seconds,
            ),
            // Beacons are excluded if "neverForLocation" flag is disabled.
            isBeacon = true,
        ) {
            CompleteLocalName("Nordic_Beacon")
            ServiceUuid(shortUuid = 0xFEAA) // Eddystone UUID
            IncludeTxPowerLevel()
        }
    }

    /**
     * Many non-connectable devices, to test the RSSI graph.
     *
     * Each device advertises with a different interval and proximity. Every third one stops
     * advertising for a while, to show breaks in the graph.
     */
    private val sensors = (1..48).map { i ->
        PeripheralSpec.simulatePeripheral(
            identifier = "00:00:00:00:00:%02X".format(i),
            proximity = Proximity.entries[i % 3],
        ) {
            val parameters = LegacyAdvertisingSetParameters(
                connectable = false,
                interval = (100 + (i * 97) % 900).milliseconds,
            )
            fun AdvertisingDataScope.data() {
                CompleteLocalName("Sensor %02d".format(i))
            }
            if (i % 3 == 0) {
                advertising(parameters = parameters, timeout = 10.seconds) { data() }
                advertising(parameters = parameters, delay = 18.seconds) { data() }
            } else {
                advertising(parameters = parameters, delay = (i * 300).milliseconds) { data() }
            }
        }
    }

    @ViewModelScoped
    @Provides
    fun provideViewModelCoroutineScope(lifecycle: ViewModelLifecycle): CoroutineScope {
        return CoroutineScope(SupervisorJob())
            .also { scope ->
                lifecycle.addOnClearedListener { scope.cancel() }
            }
    }

    @ViewModelScoped
    @Provides
    fun providesAdvertiser(environment: MockAndroidEnvironment): BluetoothLeAdvertiser {
        return BluetoothLeAdvertiser.mock(environment)
    }

    @ViewModelScoped
    @Provides
    fun provideCentralManager(
        environment: MockAndroidEnvironment,
        scope: CoroutineScope,
    ): CentralManager = CentralManager.mock(environment, scope)
        .apply {
            logger = Log.Sink.Timber { _, _ -> true }
            simulatePeripherals(listOf(blinky, beacon) + sensors)
        }

}