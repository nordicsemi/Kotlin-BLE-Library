# Module client-core

Platform-independent API of the Bluetooth LE central role: scanning for devices, connecting to
them and using their GATT services.

## Overview

This module contains the base types of the client, which are implemented by the platform-specific
modules for native and mock environments, for example `client-android`, `client-android-mock`
and the base modules `client-core-android` and `client-core-mock`. The module itself does not talk
to any Bluetooth stack. Use it to write code that does not depend on the platform, and get
an instance of the `CentralManager` from the platform-specific module.

The main types are:

| Type | Description |
|------|-------------|
| `CentralManager` | Scans for devices, monitors and ranges them, and connects to a `Peripheral`. |
| `Peripheral` | A remote device: its connection state, MTU, RSSI, services and profiles. |
| `RemoteService`, `RemoteCharacteristic`, `RemoteDescriptor` | The GATT attributes of a connected peripheral. They are read, written and subscribed to. |
| `Profile` | The logic of a service or a set of services, separated from the GATT API. |
| `ScanResult`, `AdvertisingData` | An advertising packet received during scanning. |

## Usage

### Scanning and connecting

The central manager scans for devices and connects to the peripheral. The `filter` of the scan
depends on the platform.
```kotlin
val peripheral = centralManager.scan(timeout = 10.seconds) { /* a platform-specific filter */ }
    .first()
    .peripheral

centralManager.connect(peripheral)
peripheral.state
    .onEach { state -> println("State: $state") }
    .launchIn(scope)
```

### Using the services

The services of a connected peripheral are discovered with `services`, which is a `StateFlow`.
Characteristics are read, written and subscribed to:
```kotlin
val service = peripheral.services(listOf(LBS_UUID))
    .filterIsInstance<RemoteServices.Discovered>()
    .first { it.services.isNotEmpty() }
    .services.single()
val led = service.characteristics.first { it.uuid == LED_UUID }
val button = service.characteristics.first { it.uuid == BUTTON_UUID }

led.write(byteArrayOf(1))
button.subscribe()
    .onEach { value -> println("Button: ${value.first()}") }
    .launchIn(scope)
```

### Profiles

A profile keeps the logic of a service in one place, so the application does not use
characteristics directly. It is registered with `Peripheral.profile`, and is started when
the services of the profile were discovered. Extend one of the types:

* `Profile.Simple` for a single service,
* `Profile.MultiService` for more services, some of which may be optional,
* `Profile.WithLocalServices` for a profile that also uses the services of this device.

Each of them has an example in its documentation.
```kotlin
val api = LedButtonProfile()
    .also { peripheral.profile(scope, it) }
api.ledState.value = true
```

## Server

Both sides of a Bluetooth LE connection can have a GATT server. Next to using the services of
peripherals, the central manager has a `server`: the local GATT server of this device, which
serves the remote devices that use it (*clients*, see the `server-core` module). It is created
when first accessed, so an application that does not need it does not use its resources or
request its permissions, and it is closed together with the manager unless it was given to
the manager when it was created.

The services of the server are defined using `setup`, at any time. The services returned by
`setup` and by `server.services` are **shared**: they have one value and one set of handlers for
all clients, and `update` notifies all subscribers. A peripheral that is connected to this
device can also use them. The services given to a profile of the peripheral are **per-client**
views of the shared ones: the same values and handlers, but `writes`, `subscribers` and `update`
are limited to that peripheral. They can also be obtained with
`server.services(peripheral.identifier)`.
```kotlin
centralManager.server.setup {
    Service(IMMEDIATE_ALERT_UUID) {
        Characteristic(
            uuid = ALERT_LEVEL_UUID,
            properties = CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
            permissions = Permission.WRITE,
        )
    }
}

// Only the writes of this peripheral.
centralManager.server
    .services(peripheral.identifier)
    .first { it.isNotEmpty() }
    .first { it.uuid == IMMEDIATE_ALERT_UUID }
    .characteristics.first { it.uuid == ALERT_LEVEL_UUID }
    .writes
    .onEach { println("Alert level: ${it.value.first()}") }
    .launchIn(scope)
```
A profile that needs both the services of the peripheral and the local ones extends
`Profile.WithLocalServices`, which has a full example, the Proximity Profile. The profile does not
add the local services, the application does using `setup`. If they are replaced, the profile is
started again with the new ones. The profiles that use the server only, without any peripheral,
extend `LocalProfile` and are registered using `server.profile`.

# Package no.nordicsemi.kotlin.ble.client

Common types and interfaces for Bluetooth Low Energy clients: the [CentralManager], the
[Peripheral] with its remote services, characteristics and descriptors, and the [Profile] with its
subtypes [Profile.Simple], [Profile.MultiService] and [Profile.WithLocalServices].

# Package no.nordicsemi.kotlin.ble.client.exception

This package contains exceptions that can be thrown by the client when scanning or interacting with 
Bluetooth Low Energy devices.
