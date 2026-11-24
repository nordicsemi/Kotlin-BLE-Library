/*
 * Copyright (c) 2026, Nordic Semiconductor
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

package no.nordicsemi.kotlin.ble.android.sample.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import no.nordicsemi.kotlin.ble.android.sample.common.InfoChip
import no.nordicsemi.kotlin.ble.android.sample.common.format
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.android.sample.theme.Nordic
import no.nordicsemi.kotlin.ble.core.ConnectionParameters
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.Phy
import no.nordicsemi.kotlin.ble.core.PhyInUse

@Composable
fun ServerView(
    isRunning: Boolean,
    isAdvertising: Boolean,
    isLedOn: Boolean,
    isButtonPressed: Boolean,
    subscribersCount: Int,
    centrals: List<CentralInfo>,
    errorMessage: String?,
    onButtonPressedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        errorMessage?.let {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Nordic LED Button Service",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        !isRunning -> "The server is stopped. Press Start to open the service."
                        isAdvertising -> "Advertising and waiting for connections."
                        else -> "The server is running."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfoChip("Server", if (isRunning) "Running" else "Stopped")
                    InfoChip("Advertising", if (isAdvertising) "Yes" else "No")
                    InfoChip("Button subscribers", subscribersCount.toString())
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Indicator(
                    label = "LED",
                    caption = if (isLedOn) "ON" else "OFF",
                    color = { if (isLedOn) Nordic.Color.Sun else Nordic.Color.LightGrey },
                )
                ButtonTarget(
                    enabled = isRunning,
                    isPressed = isButtonPressed,
                    onPressedChanged = onButtonPressedChanged,
                )
            }
        }

        Text(
            text = "Connected centrals",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (centrals.isEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Text(
                    text = "No centrals connected",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        centrals.forEach { central ->
            CentralItem(central)
        }
    }
}

/** A card with the address of a central, its connection state and the parameters of the connection. */
@Composable
private fun CentralItem(
    central: CentralInfo,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        animationSpec = tween(durationMillis = 500),
        targetValue = when (central.state) {
            is ConnectionState.Connected -> Nordic.Color.Lake
            is ConnectionState.Connecting -> Nordic.Color.Blue
            else -> Nordic.Color.MiddleGrey
        },
        label = "central color animation",
    )
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(text = central.address) },
            supportingContent = { Text(text = central.state.toString()) },
            leadingContent = {
                Icon(
                    imageVector = Nordic.Icons.Bluetooth,
                    contentDescription = "Central Icon",
                    tint = Color.White,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .padding(4.dp),
                )
            },
        )
        FlowRow(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val parameters = central.connectionParameters
            InfoChip("MTU", central.mtu?.let { "$it bytes" })
            InfoChip("PHY", central.phy?.format())
            InfoChip("Interval", parameters?.let { "${it.connectionIntervalMillis} ms" })
            InfoChip("Latency", parameters?.latency?.toString())
            InfoChip("Timeout", parameters?.let { "${it.supervisionTimeoutMillis} ms" })
        }
    }
}

/** A big circle with a label, filled with the color returned by [color]. */
@Composable
private fun Indicator(
    label: String,
    caption: String,
    color: () -> Color,
) {
    val animated by animateColorAsState(targetValue = color(), label = label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(animated),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = caption,
                style = MaterialTheme.typography.titleLarge,
                color = Nordic.Color.DarkGrey,
            )
        }
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

/** A big circle which is pressed as long as the user is holding it. */
@Composable
private fun ButtonTarget(
    enabled: Boolean,
    isPressed: Boolean,
    onPressedChanged: (Boolean) -> Unit,
) {
    val currentOnPressedChanged by rememberUpdatedState(onPressedChanged)
    val color by animateColorAsState(
        targetValue = when {
            !enabled -> Nordic.Color.LightGrey
            isPressed -> Nordic.Color.Lake
            else -> Nordic.Color.Blue
        },
        label = "Button",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(color)
                .pointerInput(enabled) {
                    if (enabled) {
                        detectTapGestures(
                            onPress = {
                                currentOnPressedChanged(true)
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    currentOnPressedChanged(false)
                                }
                            },
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isPressed) "Pressed" else "Hold",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
        Text(text = "Button", style = MaterialTheme.typography.titleMedium)
    }
}

@Preview
@Composable
private fun ServerViewPreview() {
    AppTheme {
        ServerView(
            isRunning = true,
            isAdvertising = true,
            isLedOn = true,
            isButtonPressed = false,
            subscribersCount = 1,
            centrals = listOf(
                CentralInfo(
                    address = "AA:BB:CC:DD:EE:FF",
                    state = ConnectionState.Connected,
                    mtu = 247,
                    phy = PhyInUse(txPhy = Phy.PHY_LE_2M, rxPhy = Phy.PHY_LE_2M),
                    connectionParameters = ConnectionParameters.Specified(
                        connectionInterval = 24,
                        latency = 0,
                        supervisionTimeout = 400,
                    ),
                ),
            ),
            errorMessage = null,
            onButtonPressedChanged = { },
        )
    }
}

@Preview
@Composable
private fun ServerViewStoppedPreview() {
    AppTheme {
        ServerView(
            isRunning = false,
            isAdvertising = false,
            isLedOn = false,
            isButtonPressed = false,
            subscribersCount = 0,
            centrals = emptyList(),
            errorMessage = "Bluetooth is turned off",
            onButtonPressedChanged = { },
        )
    }
}
