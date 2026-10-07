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

package no.nordicsemi.kotlin.ble.android.sample.common

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority
import no.nordicsemi.kotlin.ble.environment.android.compose.LocalEnvironmentOwner

@Composable
fun DeviceActions(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    isBonded: Boolean,
    onBondRequested: () -> Unit,
    onRemoveBondRequested: () -> Unit,
    onClearCacheRequested: () -> Unit,
    onRequestConnectionPriority: (ConnectionPriority) -> Unit,
) {
    val environment = LocalEnvironmentOwner.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text(text = "Bond") },
            enabled = !isBonded,
            onClick = {
                onBondRequested()
                onDismissRequest()
            },
        )
        DropdownMenuItem(
            enabled = isBonded && environment.allowsBondRemoval,
            text = { Text(text = "Remove bond") },
            onClick = {
                onRemoveBondRequested()
                onDismissRequest()
            },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = "Clear cache") },
            onClick = {
                onClearCacheRequested()
                onDismissRequest()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = "Connection Parameters") },
            onClick = {
                onRequestConnectionPriority(ConnectionPriority.BALANCED)
                onDismissRequest()
            },
        )
    }
}

@Preview
@Composable
private fun DeviceActionsPreview() {
    AppTheme {
        DeviceActions(
            expanded = true,
            onDismissRequest = {},
            isBonded = false,
            onBondRequested = {},
            onRemoveBondRequested = {},
            onClearCacheRequested = {},
            onRequestConnectionPriority = {},
        )
    }
}