package no.nordicsemi.android.kotlin.ble.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectionPriority
import no.nordicsemi.android.kotlin.ble.test.utils.TestAddressProvider
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeadlockTwoDevices {

    private val address = TestAddressProvider.address
    private val address2 = TestAddressProvider.auxiliaryAddress

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    private val testCount = 10

    @Test
    fun deadLockTwoDevices() = runBlocking {
        val j1 = scope.launch {
            repeat(testCount) {
                val c1 = ClientBleGatt.connect(InstrumentationRegistry.getInstrumentation().targetContext, address, scope)
                if (!c1.isConnected) {
                    return@launch
                }
//                c1.requestMtu(23)
                c1.requestConnectionPriority(BleGattConnectionPriority.LOW_POWER)
                c1.disconnect()
            }
        }

        val j2 = scope.launch {
            repeat(testCount) {
                val c2 = ClientBleGatt.connect(InstrumentationRegistry.getInstrumentation().targetContext, address2, scope)
                if (!c2.isConnected) {
                    return@launch
                }
//                c2.requestMtu(23)
                c2.requestConnectionPriority(BleGattConnectionPriority.LOW_POWER)
                c2.disconnect()
            }
        }

        j1.join()
        j2.join()
    }
}