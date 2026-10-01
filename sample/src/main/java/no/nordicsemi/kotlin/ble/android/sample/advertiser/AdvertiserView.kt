/*
 * Copyright (c) 2024, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.kotlin.ble.android.sample.advertiser

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.android.sample.view.AppBarActions
import no.nordicsemi.kotlin.ble.android.sample.view.AppBarToggleButton
import no.nordicsemi.kotlin.ble.android.sample.view.ExposedDropdownMenu
import no.nordicsemi.kotlin.ble.android.sample.view.LabeledSwitch
import no.nordicsemi.kotlin.ble.core.AdvertisingInterval
import no.nordicsemi.kotlin.ble.core.AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Bluetooth5AdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.LegacyAdvertisingSetParameters
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PrimaryPhy
import no.nordicsemi.kotlin.ble.core.TxPowerLevel
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.environment.android.mock.LatestApi
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun AdvertiserView(
    isAdvertising: Boolean,
    onStartClicked: (AdvertisingSetParameters) -> Unit,
    onStopClicked: () -> Unit,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    environment: AndroidEnvironment,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var legacy by rememberSaveable { mutableStateOf(true) }
        var connectable by rememberSaveable { mutableStateOf(true) }
        var txPowerLevelMenuExpanded by rememberSaveable { mutableStateOf(false) }
        var txPowerLevel by rememberSaveable { mutableIntStateOf(TxPowerLevel.MEDIUM) }
        var intervalMenuExpanded by rememberSaveable { mutableStateOf(false) }
        var interval by rememberSaveable { mutableLongStateOf(AdvertisingInterval.MEDIUM.inWholeMilliseconds) }
        var anonymous by rememberSaveable { mutableStateOf(false) }
        var scannable by rememberSaveable { mutableStateOf(false) }
        var discoverable by rememberSaveable { mutableStateOf(false) }
        var includeTxPowerLevel by rememberSaveable { mutableStateOf(false) }
        var primaryPhyMenuExpanded by rememberSaveable { mutableStateOf(false) }
        var primaryPhy by rememberSaveable { mutableStateOf(PrimaryPhy.PHY_LE_1M) }
        var secondaryPhyMenuExpanded by rememberSaveable { mutableStateOf(false) }
        var secondaryPhy by rememberSaveable { mutableStateOf(Phy.PHY_LE_1M) }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {

                LabeledSwitch(
                    title = "Connectable",
                    enabled = !isAdvertising,
                    checked = connectable,
                    onCheckedChange = {
                        connectable = !connectable
                        if (connectable) {
                            // Packets advertised using Advertising Extension can
                            // be either scannable or connectable, but not both.
                            scannable = false
                            // Anonymous advertising doesn't support scan requests
                            // or connection requests.
                            anonymous = false
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                ExposedDropdownMenu(
                    expanded = txPowerLevelMenuExpanded,
                    onExpandedChange = { txPowerLevelMenuExpanded = it },
                    text = "Tx Power Level",
                    enabled = !isAdvertising,
                    values = listOf(TxPowerLevel.ULTRA_LOW, TxPowerLevel.LOW, TxPowerLevel.MEDIUM, TxPowerLevel.HIGH),
                    labels = listOf(
                        "Ultra Low (-21 dBm)",
                        "Low (-15 dBm)",
                        "Medium (-7 dBm)",
                        "High (1 dBm)"
                    ),
                    selectedValue = txPowerLevel,
                    onValueChanged = { txPowerLevel = it },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                ExposedDropdownMenu(
                    expanded = intervalMenuExpanded,
                    onExpandedChange = { intervalMenuExpanded = it },
                    text = "Advertising Interval",
                    enabled = !isAdvertising,
                    values = listOf(
                        AdvertisingInterval.LOW.inWholeMilliseconds,
                        AdvertisingInterval.MEDIUM.inWholeMilliseconds,
                        AdvertisingInterval.HIGH.inWholeMilliseconds
                    ),
                    labels = listOf("Low (~100 ms)", "Medium (~250 ms)", "High (~1 sec)"),
                    selectedValue = interval,
                    onValueChanged = { interval = it },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                LabeledSwitch(
                    title = "Advertising Extension",
                    subtitle = "Requires Android 8+",
                    enabled = !isAdvertising && environment.androidSdkVersion >= Build.VERSION_CODES.O,
                    checked = !legacy,
                    onCheckedChange = { legacy = !legacy },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                AnimatedVisibility(visible = !legacy) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LabeledSwitch(
                            title = "Scannable",
                            enabled = !isAdvertising,
                            checked = scannable,
                            onCheckedChange = {
                                scannable = !scannable
                                if (scannable) {
                                    // Packets advertised using Advertising Extension can
                                    // be either scannable or connectable, but not both.
                                    connectable = false
                                    // Anonymous advertising doesn't support scan requests
                                    // or connection requests.
                                    anonymous = false
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        LabeledSwitch(
                            title = "Discoverable",
                            subtitle = "Requires Android 14+",
                            enabled = !isAdvertising && environment.androidSdkVersion >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                            checked = discoverable,
                            onCheckedChange = { discoverable = !discoverable },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        LabeledSwitch(
                            title = "Anonymous",
                            enabled = !isAdvertising,
                            checked = anonymous,
                            onCheckedChange = {
                                anonymous = !anonymous
                                if (anonymous) {
                                    // Anonymous advertising doesn't support scan requests
                                    // or connection requests.
                                    connectable = false
                                    scannable = false
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        LabeledSwitch(
                            title = "Include Tx Power",
                            enabled = !isAdvertising,
                            checked = includeTxPowerLevel,
                            onCheckedChange = { includeTxPowerLevel = !includeTxPowerLevel },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        ExposedDropdownMenu(
                            expanded = primaryPhyMenuExpanded,
                            onExpandedChange = { primaryPhyMenuExpanded = it },
                            text = "Primary PHY",
                            enabled = !isAdvertising,
                            values = PrimaryPhy.entries,
                            labels = listOf("PHY LE 1M", "PHY LE Coded"),
                            selectedValue = primaryPhy,
                            onValueChanged = { primaryPhy = it },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        ExposedDropdownMenu(
                            expanded = secondaryPhyMenuExpanded,
                            onExpandedChange = { secondaryPhyMenuExpanded = it },
                            text = "Secondary PHY",
                            enabled = !isAdvertising,
                            values = Phy.entries,
                            labels = listOf("PHY LE 1M", "PHY LE 2M", "PHY LE Coded"),
                            selectedValue = secondaryPhy,
                            onValueChanged = { secondaryPhy = it },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AppBarActions {
            AppBarToggleButton(
                isActive = isAdvertising,
                startLabel = "Advertise",
                stopLabel = "Stop",
                onClick = {
                    if (isAdvertising) {
                        onStopClicked()
                    } else {
                        val parameters = when (legacy) {
                            true -> LegacyAdvertisingSetParameters(
                                connectable = connectable,
                                txPowerLevel = txPowerLevel,
                                interval = interval.milliseconds,
                            )
                            false -> Bluetooth5AdvertisingSetParameters(
                                connectable = connectable,
                                txPowerLevel = txPowerLevel,
                                interval = interval.milliseconds,
                                anonymous = anonymous,
                                scannable = scannable,
                                discoverable = discoverable,
                                includeTxPowerLevel = includeTxPowerLevel,
                                primaryPhy = primaryPhy,
                                secondaryPhy = secondaryPhy,
                            )
                        }
                        onStartClicked(parameters)
                    }
                },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewAdvertiserScreen() {
    AppTheme {
        AdvertiserView(
            isAdvertising = false,
            onStartClicked = { },
            onStopClicked = { },
            errorMessage = "Error!",
            environment = LatestApi()
        )
    }
}