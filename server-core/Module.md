# Module server-core

Common types and interfaces for the Bluetooth LE peripheral role (GATT server).

## Overview

The `LocalServer` is the GATT server of this device. It hosts local services, which can be read,
written and subscribed to by remote devices called *clients*. A client is the remote device that
uses the server. It is not necessarily the central of the connection: both sides of a connection
can have a GATT server and use the server of the other side.

The module is used by platform-specific implementations for native and mock environments:
`server-android` and `server-android-mock`. Advertising is not a part of the server, use
the `advertiser` modules to let other devices find it.

## Usage

### Defining services

Define the services using the `setup` method. It returns the *shared* services, which have one
value and one set of handlers for all clients.
```kotlin
val services = localServer.setup {
    Service(LBS_UUID) {
        Characteristic(
            uuid = BUTTON_UUID,
            properties = CharacteristicProperty.READ and CharacteristicProperty.NOTIFY,
            permission = Permission.READ,
        )
        Characteristic(
            uuid = LED_UUID,
            properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
            permissions = Permission.READ and Permission.WRITE,
        )
    }
}
val characteristics = services.first().characteristics
val button = characteristics.first { it.uuid == BUTTON_UUID }
val led = characteristics.first { it.uuid == LED_UUID }
```
Calling `setup` again replaces the services. The old ones are invalidated, so use only the
services returned last.

### Reads and writes

Set the initial value of a characteristic with `setValue`. By default, a read returns the current
value and a write is accepted and stored. Use `onRead` and `onWrite` to change that:
```kotlin
led.setValue(byteArrayOf(0))

// The LED accepts exactly one byte.
led.onWrite {
    if (value.size == 1) {
        WriteResponse.Success
    } else {
        WriteResponse.Failure(OperationStatus.InvalidAttributeLength)
    }
}

// Provide the value on demand, for example based on the client that reads.
button.onRead { ReadResponse.Success(byteArrayOf(if (pressed) 1 else 0)) }
```
Accepted writes are emitted in `writes`, together with the client that sent them:
```kotlin
led.writes
    .onEach { request -> println("LED set to ${request.value.first()} by ${request.client.identifier}") }
    .launchIn(scope)
```

### Notifications

`subscribers` contains the clients that enabled notifications or indications. `update` stores
the new value and sends it to all of them:
```kotlin
button.subscribers
    .onEach { println("${it.size} client(s) subscribed") }
    .launchIn(scope)

button.update(byteArrayOf(1))
```

### Shared and per-client services

The services given by `setup` and `LocalServer.services` are shared by all clients: a value written
by one client is read by the others, and `update` notifies all subscribers.
To work with one client only, use `services(client)`, or `services(identifier)`, which returns
a view of the same services. The values and handlers are the same, but `writes`, `subscribers`
and `update` are limited to that client:
```kotlin
val clientServices = localServer.services(client)
    .first { it.isNotEmpty() }
val clientButton = clientServices.single().characteristics.first { it.uuid == BUTTON_UUID }

// Notifies this client only. Throws if the client has not subscribed.
clientButton.update(byteArrayOf(1))
```

### Profiles

A profile keeps the logic of a service in one place. A profile using local services of
the server only extends `LocalProfile.Simple` (one service) or `LocalProfile.MultiService`
(more services), and is registered using `LocalServer.profile`:
```kotlin
class LedButtonServerProfile : LocalProfile.Simple(
    serviceUuid = LBS_UUID,
    name = "LBS",
) {
    private lateinit var ledCharacteristic: LocalCharacteristic
    private lateinit var buttonCharacteristic: LocalCharacteristic

    /** The LED is controlled by the client, which writes the LED characteristic. */
    val ledState: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The Button is controlled by the server. A change notifies the subscribed clients. */
    val buttonState = MutableStateFlow(false)

    // Throw NoSuchElementException or IllegalArgumentException to reject the service.
    override fun prepare(server: LocalServer<*>, service: LocalService) {
        ledCharacteristic = service.characteristics.first { it.uuid == LED_UUID }
        buttonCharacteristic = service.characteristics.first { it.uuid == BUTTON_UUID }
    }

    // Cancelled when the services are replaced or the profile is unregistered.
    override suspend fun CoroutineScope.initialize(server: LocalServer<*>) {
        ledCharacteristic.setValue(byteArrayOf(0))
        buttonCharacteristic.setValue(byteArrayOf(0))
        // The state follows the value of the characteristic, which can also be set by the server.
        ledCharacteristic.value
            .onEach { value -> ledState.value = value.singleOrNull() == 1.toByte() }
            .launchIn(this)
        buttonState
            .onEach { pressed -> buttonCharacteristic.update(byteArrayOf(if (pressed) 1 else 0)) }
            .launchIn(this)
    }
}

val profile = LedButtonServerProfile()
localServer.profile(scope, profile)
```
The profile does not define the services. It starts when all of its services exist, and is started
again with the new services when `setup` is called again.

# Package no.nordicsemi.kotlin.ble.server

Common types and interfaces for the Bluetooth LE GATT server: the `LocalServer`, its services,
characteristics and descriptors, the requests of the clients and the `LocalProfile`.

# Package no.nordicsemi.kotlin.ble.server.exception

This package contains exceptions that can be thrown by the server when a client is not available or
an operation failed.
