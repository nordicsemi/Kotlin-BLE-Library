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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.android.sample.theme.Nordic

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
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = when {
                !isRunning -> "Server stopped. Press Start to open the Nordic LED Button Service."
                isAdvertising -> "Advertising LED Button Service"
                else -> "Server running"
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
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

        Text(
            text = "Button subscribers: $subscribersCount",
            style = MaterialTheme.typography.titleMedium,
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Connected centrals",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (centrals.isEmpty()) {
                    Text(
                        text = "No centrals connected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                centrals.forEach { central ->
                    Column {
                        Text(
                            text = central.address,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "${central.state}, MTU: ${central.mtu ?: "-"}, PHY: ${central.phy ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
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
                    state = "Connected",
                    mtu = 247,
                    phy = "TX PHY=PHY_LE_2M, RX PHY=PHY_LE_2M",
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
