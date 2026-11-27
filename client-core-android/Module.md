# Module client-core-android

Common Android types and interfaces for Bluetooth Low Energy clients.

## Overview

This module adds the Android-specific details to the types of `client-core`: how to filter
the scan, how to connect, and what a peripheral exposes on Android. It is used by the native
(`client-android`) and mock (`client-android-mock`) implementations, so the code written against it
runs on a real device and in tests.

| Type | What it adds to `client-core` |
|------|-------------------------------|
| `CentralManager` | Scan filters and `ScanOptions`, `ConnectionOptions` (`Direct` and `AutoConnect`) and the list of bonded peripherals. |
| `Peripheral` | The MAC `address`, `bondState` and `encryptionState`, the PHY and connection parameters, and requests for the highest value length (MTU), PHY, connection priority, bonding and clearing the service cache. |
| `ScanResult`, `AdvertisingData` | The Android scan result with its advertising data. |

The server of the central manager, `centralManager.server`, is the Android `LocalServer` of the
`server-core-android` module.

## Usage

### Scanning and connecting

The scan filter is evaluated by the system where possible, so use it, especially for long scans:
```kotlin
val peripheral = centralManager.scan(timeout = 10.seconds) {
    ServiceUuid(LBS_UUID)
}
    .first()
    .peripheral

centralManager.connect(peripheral, CentralManager.ConnectionOptions.Direct(timeout = 20.seconds))
```
Use `ConnectionOptions.AutoConnect` to connect when the device becomes available, for example to
a bonded device that is not in range.

### Using the connection

The peripheral exposes the state of the Android link, and lets the app change it:
```kotlin
peripheral.bondState
    .onEach { state -> println("Bond state: $state") }
    .launchIn(scope)

peripheral.createBond()
peripheral.requestHighestValueLength()
peripheral.setPreferredPhy(txPhy = Phy.PHY_LE_2M)
peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
```

# Package no.nordicsemi.kotlin.ble.client.android

Common types and interfaces for Bluetooth Low Energy clients related to Android-specific API: the
[CentralManager], the [Peripheral] and the scan filters.

# Package no.nordicsemi.kotlin.ble.client.android.exception

This package contains exceptions that can be thrown by Android clients when scanning or interacting 
with Bluetooth Low Energy devices.

# Package no.nordicsemi.kotlin.ble.client.android.preview

Types that can be used for Previews in Android Jetpack Compose whenever a Peripheral instance is 
required.
