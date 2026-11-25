# Module server-android

Android implementation of the Bluetooth LE peripheral role (GATT server).

## Overview

This module contains the native implementation of the `LocalServer` based on the Android
`BluetoothGattServer`. See `server-core` for the API of the server.

The server requires the `BLUETOOTH_CONNECT` permission on Android 12 or newer. Advertising is not
a part of the server, use `advertiser-android` to let other devices find it.

## Usage

### Creating the server

Create an instance of the `LocalServer`:
```kotlin
val localServer = LocalServer.native(environment, scope)
```
The server is closed when the `scope` is cancelled, or when `close()` is called.

If the app also uses the central role, the server is available as `centralManager.server`. It is
created when first accessed.

### Hosting a service

Define the services, handle the requests of the clients and advertise:
```kotlin
val services = localServer.setup {
    Service(LBS_UUID) {
        Characteristic(LED_UUID, CharacteristicProperty.READ and CharacteristicProperty.WRITE,
                       Permission.READ and Permission.WRITE)
    }
}
val led = services.first().characteristics.first { it.uuid == LED_UUID }
led.writes
    .onEach { println("LED: ${it.value.first()} from ${it.client.identifier}") }
    .launchIn(scope)

advertiser.advertise(
    connectable = true,
    payload = AdvertisingPayload(advertisingData = { ServiceUuid(LBS_UUID) }),
)
```

# Package no.nordicsemi.kotlin.ble.server.android

This package contains a Factory method to create the Android implementation of the Bluetooth LE
GATT server.
