# Module server-android-mock

Mock Android implementation of the Bluetooth LE peripheral role (GATT server).

## Overview

This module contains a mock `LocalServer`, which can be used in unit tests and in the apps running
on devices without Bluetooth. It runs the same server logic as the native implementation on top of
a simulated GATT server, and lets the tests simulate clients: remote devices that connect to the
server, read, write and subscribe.

Bluetooth state and permissions are taken from the mock Android environment. When Bluetooth is turned
off, all simulated clients are disconnected and `setup` throws `BluetoothUnavailableException`.

## Usage

### Creating a mock server

```kotlin
val localServer = LocalServer.mock(environment = MockAndroidEnvironment.Api31(), scope = scope)
```
The environment is optional and defaults to the latest API. The mock server is a `LocalServer`,
the same as the one created by `LocalServer.native(...)` in `server-android`, so it can be given
to the code under test.

### Simulating a client

```kotlin
val services = localServer.setup {
    Service(LBS_UUID) {
        Characteristic(LED_UUID, CharacteristicProperty.READ and CharacteristicProperty.WRITE,
                       Permission.READ and Permission.WRITE)
    }
}

val client = localServer.simulateClient()
client.connect()

// The GATT server of the phone, as seen by the client. It has the API of a client's Peripheral.
val led = client.server.services(listOf(LBS_UUID))
    .filterIsInstance<RemoteServices.Discovered>()
    .first { it.services.isNotEmpty() }
    .services.single()
    .characteristics.first { it.uuid == LED_UUID }
led.write(byteArrayOf(1), WriteType.WITH_RESPONSE)
```
`simulateClient` creates a client with a random or given address. Use `simulatedClient(address)`
to get an existing one, or to create it if it does not exist. The client is also seen in
`localServer.connectedClients`, and can simulate changes of the connection:
```kotlin
client.simulateMtuChange(247)
client.simulatePhyChange(Phy.PHY_LE_2M)
client.disconnect(ConnectionState.Disconnected.Reason.LinkLoss)
```

### Using the server with a mock central manager

The mock central manager creates a mock server when `centralManager.server` is accessed. It is
available as `centralManager.mockServer`. A simulated peripheral can use it as a client:
```kotlin
val tag = PeripheralSpec.simulatePeripheral("01:02:03:04:05:06") {
    advertising { CompleteLocalName("Tag") }
    connectable(name = "Tag")
    client { server ->
        // Runs every time the peripheral is connected. The server is the GATT server of the phone.
        val discovered = server.services(listOf(LBS_UUID))
            .filterIsInstance<RemoteServices.Discovered>()
            .first()
        discovered.services.single()
            .characteristics.first { it.uuid == LED_UUID }
            .write(byteArrayOf(1), WriteType.WITHOUT_RESPONSE)
    }
}
```

# Package no.nordicsemi.kotlin.ble.server.android.mock

This package contains a Factory method to create a mock Android implementation of the Bluetooth LE
GATT server and the simulated clients.
