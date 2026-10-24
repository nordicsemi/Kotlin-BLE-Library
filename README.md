![Maven Central Version](https://img.shields.io/maven-central/v/no.nordicsemi.kotlin.ble/client-android?link=https%3A%2F%2Fcentral.sonatype.com%2Fsearch%3Fq%3Dno.nordicsemi.kotlin.ble)
<img referrerpolicy="no-referrer-when-downgrade" src="https://static.scarf.sh/a.png?x-pxid=5422a99b-277e-4da2-8fed-8bf35105488b" />

# Kotlin BLE Library for Android

The library simplifies usage of Android Bluetooth Low Energy on Android. It is a wrapper around
native API and uses Kotlin Coroutines for asynchronous operations. The usage is designed to be more
natural according to the BLE specification.

> [!Important]
> This library is now in Beta. Unless some serious issue is found, API should not change and should 
> be backwards compatible.

## Version 2

We are working on version 2 of the library. The new version will be a complete rewrite of the library.

Current status:
- [ ] Central role
    - [ ] Scanning
       - [x] Scanning for nearby Bluetooth LE devices
       - [ ] Scan settings / background scan
       - [ ] Ranging and monitoring device
       - [ ] Obtaining list of connected devices
    - [x] Establishing connection
       - [x] Direct (`autoConnect = false`)
       - [x] Using _AutoConnect_ feature
       - [x] Service discovery and subscribing to services changes
       - [x] Bonding and observing bond state
          - [x] [Advanced encryption events added in Android 16](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#ACTION_ENCRYPTION_CHANGE)
    - [x] Basic GATT operations
       - [x] Reading / writing characteristics
       - [x] Enabling notifications / indications
       - [x] Subscribing to value changes
       - [x] Requesting highest MTU
    - [x] Advanced GATT operations
       - [x] Requesting PHY
       - [x] Subscribing to PHY changes
       - [x] Requesting connection priority
       - [x] Subscribing to connection parameter changes
       - [x] Reliable write
    - [x] Mock implementation
       - [x] Basic GATT operations
       - [x] Advanced GATT operations
       - [x] Bonding
- [ ] Peripheral role
    - [x] Advertising
    - [ ] Setting up GATT server
    - [ ] GATT operations
    - [ ] Mock implementation

## Documentation

Dokka documentation can be found [here](https://nordicsemi.github.io/Kotlin-BLE-Library/html/index.html).

### Environments

The library relies on *Environments* when accessing Bluetooth API. It provides 2 implementations:
- native, using `NativeAndroidEnvironment`
- mock, using `MockAndroidEnvironment`

It is recommended to use them in different flavors, or use the mock one in tests.

#### Native environment

To create the native environment, use:
```kotlin
val environment = NativeAndroidEnvironment.getInstance(context, isNeverForLocationFlagSet = true)
```
As a parameter, specify whether the `Manifest.permission.BLUETOOTH_SCAN` was defined with, or without
the `neverForLocation` flag. The library requires this information to check, whether the Location
Permission is needed for scanning for Bluetooth LE devices.

#### Mock environment

Define a mock environment using one of predefined implementations:
```kotlin
val environment = MockAndroidEnvironment.Api31(
    // Customize the mock environment using various flags:
    isNeverForLocationFlagSet = false,
    isBluetoothScanPermissionGranted = false,
    isBluetoothConnectPermissionGranted = false,
    isBluetoothAdvertisePermissionGranted = false,
    // ... and many more.
)
```

There are number of implementations representing platforms with significant API changes related to Bluetooth:
- Api21 - initial supported API,
- Api23 - location and `ACCESS_FINE_LOCATION` permission required for scanning,
- Api26 - PHY 2 and Coded PHY added,
- Api31 - Bluetooth permissions added.

Use `LatestApi()` as a typealias for the implementation matching the latest implementation.

#### Important

Remember, that the `NativeAndroidEnvironment` registers `BroadcastReceivers` and needs to be closed
using `close()` when the environment is no longer needed. This may be done in `onDestroy()` of the 
`Activity` when the app is being destroyed.

You may use Hilt and the `ActivityRetainedComponent`:
```kotlin
@Module
@InstallIn(ActivityRetainedComponent::class)
object EnvironmentModule {

    @ActivityRetainedScoped
    @Provides
    fun provideEnvironment(
        @ApplicationContext context: Context,
        lifecycle: ActivityRetainedLifecycle,
    ): NativeAndroidEnvironment {
        // Make sure the environment is closed when the lifecycle is cleared.
        // This will unregister the broadcast receiver.
        return NativeAndroidEnvironment.getInstance(context, true)
            .also { lifecycle.addOnClearedListener { it.close() } }
    }
}
```

### Central Manager

To use a phone as a central device you need to create a `CentralManager`. 
Central Manager allows to scan and connect to Bluetooth LE accessories.

Create an advertiser using the `Environment`:
```kotlin
val centralManager = CentralManager.Factory.native(environment, scope)
```

#### Mock Central Manager

A mock central manager may be used to test the app using mocked Bluetooth LE devices. First, 
define the `PeripheralSpec`s of test devices:
```kotlin
private val hrm = PeripheralSpec
    .simulatePeripheral(
        identifier = "11:22:33:44:55:66",
        proximity = Proximity.FAR,
    ) {
        advertising(
            parameters = LegacyAdvertisingSetParameters(
                connectable = true,
                interval = 500.milliseconds,
                txPowerLevel = -7,
            ),
        ) {
            ServiceUuid(shortUuid = 0x180D) // Heart Rate Service
            CompleteLocalName("Nordic HRM")
        }
    }
```

You may define them as connectable, or non-connectable. 
See the [Sample](https://github.com/nordicsemi/Kotlin-BLE-Library/blob/version/2.0/sample/src/mock/java/no/nordicsemi/kotlin/ble/android/sample/di/ViewModelModule.kt)
for an example how to define the connectivity parameters and define services and device behavior.

With mock peripherals defined, create a mock central manager:
```kotlin
val centralManager = CentralManager.mock(environment, scope)
    .apply {
        simulatePeripherals(listOf(hrm /*, ... */))
    }
```

### Scanning

Scanning for Bluetooth LE accessories can be done using the `scan` method of a Central Manager:

```kotlin
centralManager
    .scan(1250.milliseconds) {
        ServiceUUID(someServiceUUID)
        Any {
            Name("MyName")
            Name("OtherName")
        }
    }
    .distinctByPeripheral()
    .map {
        it.peripheral
    }
    .onEach { peripheral ->
        // Do something with the peripheral
    }
    .launchIn(scope)
```

### Connecting

Use the Central Manager also to connect to the peripherals: 
```kotlin
scope.launch {
    try {
        withTimeout(10000) {
            centralManager.connect(
                peripheral = peripheral,
                options = CentralManager.ConnectionOptions.Direct(
                    timeout = 3.seconds,
                    retry = 2,
                    retryDelay = 1.seconds,
                    Phy.PHY_LE_1M,
                ),
                // options = CentralManager.ConnectionOptions.AutoConnect,
            )
            Timber.i("Connected to ${peripheral.name}!")
        }
    
        // The first time the app connects to the peripheral it needs to initiate
        // observers for various parameters.
        // The observers will get canceled when the connection scope is canceled,
        // that is when the device is manually disconnected in case of auto connect,
        // or disconnects for any reason when auto connect was false.
        peripheral.phy
            .onEach {
                Timber.i("PHY changed to: $it")
            }
            .onEmpty {
                Timber.w("PHY didn't change")
            }
            .onCompletion {
                Timber.d("PHY collection completed")
            }
            .launchIn(this)
    } catch (e: Exception) {
        Timber.e(e, "Connection attempt failed")
    }
}
```

### Service discovery

GATT Services are available as `StateFlow<RemoteService>`. When the flow is collected, the library 
will discover services and emit the list of services. The list is updated when the *Service Changed*
indication is received or when the device gets disconnected. The flow's initial value is `null`.

It is possible to subscribe to `services()` even before the device is connected. The flow will
be emitted when the services are available. The previous services should not be used when a new
value is emitted, including the `null` value. Any GATT operation on such attribute will throw an
`InvalidAttributeException` exception.

```kotlin
peripheral.services()
    .onEach { services ->
        Timber.i("Services changed: $services")

        services.forEach { remoteService ->
            // Do something with the service. See GATT operations below.
        }
    }
    .onEmpty {
        Timber.w("No services found")
    }
    .onCompletion {
        Timber.d("Service collection completed")
    }
    .launchIn(scope)
```

### GATT operations

#### Maximum value length

Request maximum MTU using:
```kotlin
peripheral.requestHighestValueLength()
```

Note, that it is not possible to specify the MTU value. The device will automatically use the maximum
value it supports.

You may also do this automatically using a flag in connection options:

```kotlin
val options = CentralManager.ConnectionOptions.Direct(
    timeout = 3.seconds,
    retry = 2,
    retryDelay = 1.seconds,
    automaticallyRequestHighestMtu = true
)
centralManager.connect(
    peripheral = peripheral,
    options = options
)
Timber.i("Connected to ${peripheral.name}!")
```

The result can be read using:

```kotlin
val length = peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
Timber.i("Maximum write length for $writeType: $length bytes")
```

#### Writing characteristic value

To write a value to a characteristic or a descriptor, use `write(...)` method:

```kotlin
val ledCharacteristic = remoteService.characteristics.firstOrNull { it.uuid == ledCharacteristicUuid }
Timber.i("($ce) Turning LED on...")
ledCharacteristic?.write(byteArrayOf(0x01))
```

Note, that `write(...)` is synchronous and can only send up to the maximum value length for a given
write type. If you need to send longer data, use `chunked(...)` method 
([see here](https://github.com/nordicsemi/Kotlin-BLE-Library/blob/891eb663412a87ed6d6e627b180607e544aa55a6/core/src/main/java/no/nordicsemi/kotlin/ble/core/util/Transform.kt#L52))
to split the `ByteArray` or `Flow<ByteArray>` into chunks and send them in a loop.

```kotlin
val veryLongData = byteArrayOf(1, 2, 3, 4, 5 /* ... */)
val length = peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE)
veryLongData.chunked(length).forEach {
    // Writing data one chunk after another:
    characteristic.write(it)
}
```

Sometimes, a write operation to a characteristic is expected to trigger a notification or an indication.
If the notification is sent immediately, the library may not be able to subscribe for it before
it is received, resulting in data lost. In such situation, use `waitForValueChange`:

```kotlin
// Registering for a notification:
Timber.i("($ce) Awaiting for button press to start LED blinking...")
val result = buttonCharacteristic?.waitForValueChange {
    // Sending command that will trigger a notification:
    Timber.i("($ce) Turning LED on...")
    ledCharacteristic?.write(byteArrayOf(0x01))
}
Timber.i("($ce) Button change to 0x${result?.toHexString()}")
```

#### Reading characteristic value

```kotlin
remoteService.characteristics.forEach { remoteCharacteristic ->
    try {
        Timber.w("Reading value of ${remoteCharacteristic.uuid}...")
        val value = remoteCharacteristic.read()
        Timber.i("Value of ${remoteCharacteristic.uuid}: 0x${value.toHexString()}")
    } catch (e: Exception) {
        // An exception is thrown when a characteristic is not readable, or an error occurs.
        Timber.e("Failed to read ${remoteCharacteristic.uuid}: ${e.message}")
    }
}
```

#### Subscribing to value changes

```kotlin
remoteService.characteristics.forEach { remoteCharacteristic ->
    try {
        Timber.w("Subscribing to ${remoteCharacteristic.uuid}...")
        remoteCharacteristic.subscribe()
            .onEach { newValue ->
                Timber.i("Value of ${remoteCharacteristic.uuid} changed: 0x${newValue.toHexString()}")
            }
            .onEmpty {
                Timber.w("No updates from ${remoteCharacteristic.uuid}")
            }
            .catch { e ->
                // This is called when subscription fails.
                Timber.e(e, "Subscription to ${remoteCharacteristic.uuid} failed")
            }
            .onCompletion {
                Timber.d("Stopped observing updates from ${remoteCharacteristic.uuid}")
            }
            .launchIn(scope)
    } catch (e: Exception) {
        // An exception is thrown when a characteristic does not have NOTIFY or INDICATE property,
        // has no Client Characteristic Configuration descriptor, or an error occurs.
        Timber.e("Failed to subscribe to ${remoteCharacteristic.uuid}: ${e.message}")
    }
}
```

### Logging

Starting from version *2.0.0-beta01* the `CentralManager` and `Peripheral`s have a `logger` property.
Assign a custom Log Sink to get categorized logs.

Read more in [Kotlin Util Library / Log](https://github.com/nordicsemi/Kotlin-Util-Library#log).

#### Logcat / Console
```kotlin
val centralManager = CentralManager.native(environment, scope)
    .also { it.logger = Log.Sink.Default(/* filter = */) }
```

#### Timber (Android only)
```kotlin
val centralManager = CentralManager.native(environment, scope)
    .also { it.logger = Log.Sink.Timber() }
```

For more, see [_sample_](https://github.com/nordicsemi/Kotlin-BLE-Library/blob/version/2.0/sample/src/main/java/no/nordicsemi/kotlin/ble/android/sample/scanner/ScannerViewModel.kt).
