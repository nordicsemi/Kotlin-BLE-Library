# Module client-core-mock

Common types and interfaces for mock Bluetooth Low Energy clients.

## Overview

This module is used by platform-specific mock implementations.

## Server

A simulated peripheral can also be a client of the GATT server of the phone, that is of
`CentralManager.server`. Use `client` in the `PeripheralSpec`: the block is called every time
the peripheral is connected, in the scope of the central manager, and is cancelled when the
connection ends. Its parameter is the server of the phone, with the same API as a peripheral
has on the client side: `services` discovers the services, and the characteristics can be read,
written and subscribed to.
```kotlin
val tag = PeripheralSpec.simulatePeripheral("01:02:03:04:05:06") {
    advertising { CompleteLocalName("Tag") }
    connectable(name = "Tag")
    client { server ->
        val discovered = server.services(listOf(IMMEDIATE_ALERT_SERVICE_UUID))
            .filterIsInstance<RemoteServices.Discovered>()
            .first()
        discovered.services.single()
            .characteristics.first { it.uuid == ALERT_LEVEL_UUID }
            .write(byteArrayOf(2), WriteType.WITHOUT_RESPONSE)
    }
}
```

### Shared and per-client services

The services that the phone defines with `setup` are **shared**: one value and one set of
handlers for all clients. The simulated peripheral, as one of the clients, writes to them, and
the write is emitted in `writes` of the shared characteristic and of the per-client view of
this peripheral only. When the phone notifies all `subscribers`, the simulated peripheral
receives the notification if it has subscribed. The server has to be a mock one, for example
`LocalServer.mock(...)` passed to `CentralManager.mock(server = ...)`, or the one the manager
creates. For other servers the block is not called. The requests are sent without simulating
the transfer time, and reliable writes are not supported.

# Package no.nordicsemi.kotlin.ble.client.mock

Common types and interfaces for mock Bluetooth Low Energy clients.