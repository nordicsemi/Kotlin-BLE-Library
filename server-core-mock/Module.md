# Module server-core-mock

Common types and interfaces for mock Bluetooth LE GATT servers.

## Overview

This module is used by platform-specific mock implementations. It contains a simulated platform
layer of the GATT server, `MockGattServer`, and `MockClient`, a simulated remote device that
connects to the server and sends requests to it. The logic of the server itself is shared with the
native implementations, so the tests exercise the same code.

All requests of a mock client are executed without delays, which makes them work with the virtual
time of `runTest`.

## Usage

Mock clients are created by the platform-specific servers, see `server-android-mock`. A client has
to be connected before it sends requests. Attributes are found by UUIDs:
```kotlin
val client = server.simulateClient()
client.connect()

val led = client.findCharacteristic(LBS_UUID, LED_UUID)!!
val result = client.write(led, byteArrayOf(1))
check(result.isSuccess)

val value = client.read(led).getOrThrow()
```
Notifications are enabled with `subscribe`, which writes the Client Characteristic Configuration
descriptor, and are received in the `updates` flow:
```kotlin
val button = client.findCharacteristic(LBS_UUID, BUTTON_UUID)!!
client.subscribe(button)

val update = client.updates.first() // A MockUpdate, emitted when the server notifies.
```
A client can also simulate a link loss, a change of the MTU, or use long and reliable writes:
```kotlin
client.exchangeMtu(247)
client.simulateLinkLoss()
```

# Package no.nordicsemi.kotlin.ble.server.mock

This package contains the simulated GATT server and the simulated clients.
