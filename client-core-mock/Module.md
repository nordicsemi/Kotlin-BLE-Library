# Module client-core-mock

Common types for simulating Bluetooth LE peripherals, to test the code that uses the client API
without a real device.

## Overview

A mock central manager, created by the platform-specific mock modules like `client-android-mock`,
behaves like a native one, but scans for and connects to simulated peripherals. This module
contains the types that describe them:

| Type | Description |
|------|-------------|
| `PeripheralSpec` | The specification of a simulated peripheral: its advertising, connection parameters, bonding and services. |
| `MockPeripheralServer` | The GATT server hosted by a peripheral. Its services are defined, and their values and handlers are set, like in the peripheral role. |
| `PeripheralSpecEventHandler` | Decides how the peripheral reacts to connection requests, bonding, service discovery and resets. |
| `SimulationProvider` | Implemented by the mock central manager: `simulatePeripherals` adds the peripherals, `tearDownSimulation` removes them. |

The simulated requests take as much time as the transfers of a real peripheral, using
the connection interval, the MTU and the PHY of the connection. In tests using `runTest`, run
the mock central manager in `backgroundScope`, so they use the virtual time.

## Usage

### Simulating a peripheral

A peripheral is described using `PeripheralSpec.simulatePeripheral`. The advertising data is what
the scanner receives, and `connectable` makes the device connectable and sets its GATT server.
The services of the server are defined using `setup`, and their characteristics answer the requests
of the clients using the values and the handlers:
```kotlin
val server = MockPeripheralServer(scope = backgroundScope)
val services = server.setup {
    GenericAccessService()
    GenericAttributeService()
    Service(LBS_UUID) {
        Characteristic(
            uuid = LED_UUID,
            properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
            permissions = Permission.READ and Permission.WRITE,
        )
        Characteristic(
            uuid = BUTTON_UUID,
            properties = CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
            permission = Permission.READ,
        )
    }
}
val lbs = services.single { it.uuid == LBS_UUID }
val led = lbs.characteristics.first { it.uuid == LED_UUID }
val button = lbs.characteristics.first { it.uuid == BUTTON_UUID }

// The LED accepts exactly one byte.
led.onWrite {
    if (value.size == 1) WriteResponse.Success
    else WriteResponse.Failure(OperationStatus.InvalidAttributeLength)
}

val blinky = PeripheralSpec.simulatePeripheral(identifier = "01:02:03:04:05:06") {
    advertising { CompleteLocalName("Blinky") }
    connectable(name = "Blinky", maxAttMtu = 247, server = server)
}
```

### Using it from the client code

The peripherals are added to the mock central manager. The client code, for example a `Profile`,
does not know that the device is simulated:
```kotlin
val centralManager = CentralManager.mock(scope = backgroundScope)
centralManager.simulatePeripherals(listOf(blinky))

val peripheral = centralManager.scan().first().peripheral
centralManager.connect(peripheral)
val api = LedButtonProfile()
    .also { peripheral.profile(backgroundScope, it) }

// The simulated device changes its state, which is sent to the subscribed client.
button.update(byteArrayOf(1))
```

### Controlling the behavior

`PeripheralSpecEventHandler` decides how the peripheral reacts to the requests that do not concern
the GATT server: it may deny a connection, reject bonding, fail the service discovery, or react
to a link loss and a reset.
```kotlin
val spec = PeripheralSpec.simulatePeripheral(identifier = "01:02:03:04:05:06") {
    advertising { CompleteLocalName("Blinky") }
    connectable(
        name = "Blinky",
        server = server,
        eventHandler = object : PeripheralSpecEventHandler {
            override fun onConnectionRequest(peripheral: MockPeripheral, phy: PrimaryPhy) = ConnectionResult.Deny
        },
    )
}
```
The `PeripheralSpec` also simulates the events on the device side, for example
`simulateProximityChange`, `simulateConnection` and `simulateDisconnection`,
`simulateMtuRequest`, `simulateServiceChange`, `simulateBondRemoval` and `simulateReset`. A device
may also be already `connected`, `bonded`, or cached by the phone, and the services it cached may
differ from the actual ones.

## Server

Both sides of a Bluetooth LE connection can have a GATT server, so a simulated peripheral can also
be a client of the GATT server of the phone, that is of `CentralManager.server`. Use `client` in
the `PeripheralSpec`: the block is called every time the peripheral is connected, in the scope of
the central manager, and is cancelled when the connection ends. Its parameter is the server of
the phone, with the same API as a peripheral has on the client side: `services` discovers
the services, and the characteristics can be read, written and subscribed to.
```kotlin
val tag = PeripheralSpec.simulatePeripheral("01:02:03:04:05:06") {
    advertising { CompleteLocalName("Tag") }
    connectable(name = "Tag") { }
    client { server ->
        val discovered = server.services(listOf(IMMEDIATE_ALERT_SERVICE_UUID))
            .filterIsInstance<RemoteServices.Discovered>()
            .first { it.services.isNotEmpty() }
        discovered.services.single()
            .characteristics.first { it.uuid == ALERT_LEVEL_UUID }
            .write(byteArrayOf(2), WriteType.WITHOUT_RESPONSE)
    }
}
```
The services that the phone defines with `setup` are **shared**: one value and one set of
handlers for all clients. The simulated peripheral, as one of the clients, writes to them, and
the write is emitted in `writes` of the shared characteristic and of the per-client view of
this peripheral only. When the phone notifies all `subscribers`, the simulated peripheral
receives the notification if it has subscribed. The server has to be a mock one, for example
`LocalServer.mock(...)` passed to `CentralManager.mock(server = ...)`, or the one the manager
creates. For other servers the block is not called. The requests are sent without simulating
the transfer time, and reliable writes are not supported.

# Package no.nordicsemi.kotlin.ble.client.mock

Types for simulating Bluetooth Low Energy peripherals: the [PeripheralSpec], the
[MockPeripheralServer] hosted by it and the [PeripheralSpecEventHandler].
