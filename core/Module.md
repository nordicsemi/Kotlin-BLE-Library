# Module core

Core types and interfaces for Bluetooth Low Energy clients and server implementations.

## Overview

The module contains what the client and the server modules have in common. It does not depend on
any platform, and it is not used on its own.

| Types | Description |
|-------|-------------|
| `Manager`, `Environment` | The state of Bluetooth and the platform the library runs on, which is native or mock. |
| `ConnectionState`, `ConnectionParameters`, `Phy`, `PhyInUse`, `WriteType` | The connection and the link layer. |
| `Service`, `Characteristic`, `Descriptor`, `CharacteristicProperty`, `Permission` | The GATT attributes and their definitions. `ServerScope` is the DSL to define them. |
| `AdvertisingDataScope`, `AdvertisingSetParameters` | The advertising data and parameters, used by the advertiser and by the scan results. |
| `Profile` | The base class of the profiles of the client, `client-core`, and of the server, `server-core`, which separates the services from the application logic. |
| `OperationStatus` and the exceptions | The results and the errors of the GATT operations. |

# Package no.nordicsemi.kotlin.ble.core

Core types and interfaces for Bluetooth Low Energy clients and server implementations.

# Package no.nordicsemi.kotlin.ble.core.exception

Generic Bluetooth Low Energy exceptions.

# Package no.nordicsemi.kotlin.ble.core.util

Utilities.