# Module server-core-android

Common Android types and interfaces for the Bluetooth LE peripheral role (GATT server).

## Overview

On Android a client of the GATT server is identified by its MAC address, and the server reports
the clients that are connected to it. This module adds these Android specific details to
the types of `server-core`. It is used by the native implementation (`server-android`) and
by the mock implementation (`server-android-mock`).

## Usage

The `LocalServer` of this module is a `LocalServer<String>`, so everything described in `server-core`
applies. In addition it provides the list of connected clients with their connection details:
```kotlin
localServer.connectedClients
    .onEach { clients ->
        clients.forEach { client ->
            println("${client.address}: ${client.state.value}, MTU ${client.mtu.value}")
        }
    }
    .launchIn(scope)
```
Each client can be disconnected, or asked to use another PHY:
```kotlin
val client = localServer.connectedClients.value.first()
client.setPreferredPhy(txPhy = Phy.PHY_LE_2M)
client.disconnect()
```

# Package no.nordicsemi.kotlin.ble.server.android

Android specific interfaces of the Bluetooth LE GATT server: the `LocalServer` and the `Client`.
