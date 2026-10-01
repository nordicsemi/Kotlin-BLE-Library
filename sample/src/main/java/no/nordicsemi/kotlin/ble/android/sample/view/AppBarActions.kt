/*
 * Copyright (c) 2026 Nordic Semiconductor
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

package no.nordicsemi.kotlin.ble.android.sample.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Holds the actions shown in the app bar by the current screen.
 */
@Stable
class AppBarState {
    /** The actions of the current screen, or `null` if the screen has none. */
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)
}

/**
 * The [AppBarState] of the app bar shown above the current screen.
 */
val LocalAppBarState = staticCompositionLocalOf { AppBarState() }

/**
 * Shows the given actions in the app bar while this composable is in the composition.
 *
 * ```kotlin
 * AppBarActions {
 *     TextButton(onClick = onScan) { Text("Scan") }
 * }
 * ```
 */
@Composable
fun AppBarActions(content: @Composable RowScope.() -> Unit) {
    val state = LocalAppBarState.current
    val currentContent by rememberUpdatedState(content)
    DisposableEffect(state) {
        val actions: @Composable RowScope.() -> Unit = { currentContent() }
        state.actions = actions
        onDispose {
            // When navigating, the new screen may set its actions before the old one is disposed.
            if (state.actions === actions) {
                state.actions = null
            }
        }
    }
}

/**
 * A button for the app bar starting and stopping an operation, like scanning or advertising.
 *
 * While the operation is active, a progress indicator is shown next to the button.
 *
 * @param isActive Whether the operation is active.
 * @param startLabel The label of the button when the operation is not active.
 * @param stopLabel The label of the button when the operation is active.
 * @param onClick Called when the button is clicked.
 */
@Composable
fun AppBarToggleButton(
    isActive: Boolean,
    startLabel: String,
    stopLabel: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visible = isActive) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        TextButton(onClick = onClick) {
            Text(text = if (isActive) stopLabel else startLabel)
        }
    }
}
