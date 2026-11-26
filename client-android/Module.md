# Module client-android

Android implementation for the Bluetooth Low Energy GATT client.

This module provides the necessary classes and methods to interact with Bluetooth Low Energy (BLE) 
devices as a client on Android platforms, including scanning, connecting and exchanging data.

## Overview

The module contains the native implementation of the `CentralManager`, based on the Android
Bluetooth API. The API itself is described in `client-core` and `client-core-android`, so the same
code runs with the mock implementation from `client-android-mock` in tests. The app needs the
`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions on Android 12 or newer, and the location
permission on older versions.

## Usage

Create the central manager using the native environment from `environment-android`:
```kotlin
val environment = NativeAndroidEnvironment.getInstance(context, isNeverForLocationFlagSet = true)
val centralManager = CentralManager.native(environment, scope)
```
The manager is closed when the `scope` is cancelled. Then scan, connect and use the services, as
described in `client-core`:
```kotlin
val peripheral = centralManager.scan(timeout = 10.seconds) { ServiceUuid(LBS_UUID) }
    .first()
    .peripheral

centralManager.connect(peripheral)
peripheral.profile(scope, LedButtonProfile())
```
The local GATT server of the device is available as `centralManager.server`. It is created when
first accessed, so an app that does not use it does not need the permissions of the peripheral
role.

# Package no.nordicsemi.kotlin.ble.client.android

This package contains a Factory method to create an Android implementation for the Central Manager.
