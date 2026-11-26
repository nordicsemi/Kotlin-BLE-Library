# Module server-core-mock

Common types and interfaces for mock Bluetooth LE GATT servers.

## Overview

This module is used by platform-specific mock implementations. It contains a simulated platform
layer of the GATT server, `MockGattServer`, and `MockClient`, a simulated remote device that
connects to the server and uses it as a client. The logic of the server itself is shared with the
native implementations, so the tests exercise the same code.

All requests of a mock client are executed without delays, which makes them work with the virtual
time of `runTest`.

## Usage

Mock clients are created by the platform-specific servers, see `server-android-mock`. A client has
to be connected before it uses the server. Its `server` property is the GATT server of the phone as
the client sees it, with the same API as a peripheral has in the client modules: the services are
discovered, and the characteristics are read, written and subscribed to. Requests are sent without
simulating the transfer time:
```kotlin
val client = localServer.simulateClient()
client.connect()

val led = client.server.services(listOf(LBS_UUID))
    .filterIsInstance<RemoteServices.Discovered>()
    .first { it.services.isNotEmpty() }
    .services.single()
    .characteristics.first { it.uuid == LED_UUID }

led.write(byteArrayOf(1), WriteType.WITH_RESPONSE)
val value = led.read()
```
Notifications are received by subscribing to the characteristic, which writes the Client
Characteristic Configuration descriptor:
```kotlin
val button = client.server.services(listOf(LBS_UUID))
    .filterIsInstance<RemoteServices.Discovered>()
    .first { it.services.isNotEmpty() }
    .services.single()
    .characteristics.first { it.uuid == BUTTON_UUID }

button.subscribe().collect { value -> println("Button: ${value.first()}") }
```
A client can also simulate a link loss, or a change of the MTU:
```kotlin
client.exchangeMtu(247)
client.simulateLinkLoss()
```
The `server` is valid for one connection: after the client disconnects, it reports
the disconnection, and a new one is created when the client connects again. The module depends on
`client-core` for that.

# Package no.nordicsemi.kotlin.ble.server.mock

This package contains the simulated GATT server and the simulated clients.
