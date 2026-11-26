# Module client-android-mock

Android implementation for the Bluetooth Low Energy mock client.

This module provides the necessary classes and methods to interact with mock Bluetooth Low Energy (BLE) 
devices as a client on Android platforms, including emulating scanning, connecting and exchanging data.

Mock implementations are useful for testing purposes without requiring actual BLE hardware.

## Overview

The `MockCentralManager` is a `CentralManager`, the same as the native one from `client-android`,
so the code under test does not know that the devices are simulated. The simulated peripherals
are described with the `PeripheralSpec` of `client-core-mock`, and the permissions and the state
of Bluetooth come from the mock environment of `environment-android-mock`.

## Usage

Create the mock central manager and add the simulated peripherals. In tests using `runTest`, use
`backgroundScope`, so that advertising, transfers and timeouts use the virtual time:
```kotlin
val environment = MockAndroidEnvironment.Api31(isBluetoothScanPermissionGranted = true)
val centralManager = CentralManager.mock(environment, scope = backgroundScope)
centralManager.simulatePeripherals(listOf(blinky)) // See PeripheralSpec in client-core-mock.

val peripheral = centralManager.scan().first().peripheral
centralManager.connect(peripheral)
```
The local GATT server is a mock server, `centralManager.mockServer`. It is created when first
accessed, or can be given to the manager. The simulated peripherals can use it as clients, see
`PeripheralSpec.Builder.client`:
```kotlin
centralManager.mockServer.setup {
    Service(IMMEDIATE_ALERT_UUID) { /* characteristics */ }
}
```
`tearDownSimulation` removes the simulated peripherals.

# Package no.nordicsemi.kotlin.ble.client.android.mock

This package contains a Factory method to create a mock Android implementation for the Central Manager.
