# Module client-core

Common types and interfaces for Bluetooth Low Energy clients.

This module is used by platform-specific implementations for native and mock environments.

## Server

Both sides of a Bluetooth LE connection can have a GATT server. Next to using the services of
peripherals, the central manager has a `server`: the local GATT server of this device, which
serves the remote devices that use it (*clients*, see the `server-core` module). It is created
when first accessed, so an application that does not need it does not use its resources or
request its permissions, and it is closed together with the manager unless it was given to
the manager when it was created.

### Defining services

Define the services of the server using `setup`, at any time. The services returned by `setup`
and by `server.services` are **shared**: they have one value and one set of handlers for all
clients, and `update` notifies all subscribers.
```kotlin
val services = centralManager.server.setup {
    Service(LINK_LOSS_UUID) {
        Characteristic(
            uuid = ALERT_LEVEL_UUID,
            properties = CharacteristicProperty.READ and CharacteristicProperty.WRITE,
            permissions = Permission.READ and Permission.WRITE,
        )
    }
    Service(IMMEDIATE_ALERT_UUID) {
        Characteristic(
            uuid = ALERT_LEVEL_UUID,
            properties = CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
            permissions = Permission.WRITE,
        )
    }
}
```

### Per-client services

A peripheral that is connected to this device can also use its server. The services given
to a profile of the peripheral are **per-client** views of the shared ones: the same values and
handlers, but `writes`, `subscribers` and `update` are limited to that peripheral. They can also
be obtained with `server.services(peripheral.identifier)`.
```kotlin
val alertLevel = centralManager.server
    .services(peripheral.identifier)
    .first { it.isNotEmpty() }
    .first { it.uuid == IMMEDIATE_ALERT_UUID }
    .characteristics.first { it.uuid == ALERT_LEVEL_UUID }

// Only the writes of this peripheral.
alertLevel.writes
    .onEach { println("Alert level: ${it.value.first()}") }
    .launchIn(scope)
```

### Profiles with local services

A profile that needs both the services of the peripheral and the local ones extends
`Profile.WithLocalServices`. It starts when the remote services were discovered and all the local
services are available, and its `localServices` are the per-client views of the peripheral. For
example, in the Proximity Profile both devices have the Link Loss Service, and may have the
Immediate Alert Service:
```kotlin
enum class AlertLevel(val value: Byte) {
    NONE(0), MILD(1), HIGH(2);

    companion object {
        fun of(value: Byte) = entries.firstOrNull { it.value == value } ?: NONE
    }
}

class ProximityProfile : Profile.WithLocalServices(
    // Both devices have the Link Loss Service, and may have the Immediate Alert and TX Power
    // services. This sample does not use the TX Power Service.
    requiredServiceUuids = listOf(LINK_LOSS_UUID),
    optionalServiceUuids = listOf(IMMEDIATE_ALERT_UUID, TX_POWER_UUID),
    localServiceUuids = listOf(LINK_LOSS_UUID, IMMEDIATE_ALERT_UUID),
    name = "Proximity",
) {
    private lateinit var linkLossCharacteristic: RemoteCharacteristic
    private var remoteAlertCharacteristic: RemoteCharacteristic? = null
    private lateinit var localAlertCharacteristic: LocalCharacteristic

    /** The alert level requested by the other device, which this device should signal. */
    val localAlertState: StateFlow<AlertLevel>
        field = MutableStateFlow(AlertLevel.NONE)

    override fun prepare(
        peripheral: Peripheral<*, *>,
        remoteServices: List<RemoteService>,
        localServices: List<LocalService>,
    ) {
        linkLossCharacteristic = remoteServices.first { it.uuid == LINK_LOSS_UUID }
            .characteristics.first { it.uuid == ALERT_LEVEL_UUID }
        remoteAlertCharacteristic = remoteServices.firstOrNull { it.uuid == IMMEDIATE_ALERT_UUID }
            ?.characteristics?.first { it.uuid == ALERT_LEVEL_UUID }
        localAlertCharacteristic = localServices.first { it.uuid == IMMEDIATE_ALERT_UUID }
            .characteristics.first { it.uuid == ALERT_LEVEL_UUID }
    }

    override suspend fun CoroutineScope.initialize(peripheral: Peripheral<*, *>) {
        // The local characteristic is the view of this peripheral, so only its writes are emitted.
        localAlertCharacteristic.writes
            .onEach { localAlertState.value = AlertLevel.of(it.value.first()) }
            .launchIn(this)
        // The other device will alert when the link is lost.
        linkLossCharacteristic.write(byteArrayOf(AlertLevel.HIGH.value), WriteType.WITH_RESPONSE)
    }

    /** Asks the other device to alert. */
    suspend fun alert(level: AlertLevel) {
        val characteristic = checkNotNull(remoteAlertCharacteristic) { "No Immediate Alert Service" }
        characteristic.write(byteArrayOf(level.value), WriteType.WITHOUT_RESPONSE)
    }
}

peripheral.profile(scope, ProximityProfile())
```
The profile does not add the local services, the application does using `setup`. If they
are replaced, the profile is started again with the new ones. The profiles that use the server
only, without any peripheral, extend `LocalProfile` and are registered using `server.profile`.

# Package no.nordicsemi.kotlin.ble.client

Common types and interfaces for Bluetooth Low Energy clients.

# Package no.nordicsemi.kotlin.ble.client.exception

This package contains exceptions that can be thrown by the client when scanning or interacting with 
Bluetooth Low Energy devices.