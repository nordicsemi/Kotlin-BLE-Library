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

package no.nordicsemi.kotlin.ble.android.sample.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object Nordic {
    object Color {
        val Black = Color(0xFF000000)
        val Blue = Color(0xFF00A9CE)
        val Blue80 = Color(0xFF33B4D1)
        val Blueslate = Color(0xFF0033A0)
        val Sky = Color(0xFF6AD1E3)
        val Lake = Color(0xFF0077C8)
        val Lake80 = Color(0xFF3392D3)
        val Green = Color(0xFF00A651)
        val Grass = Color(0xFFD0DF00)
        val Sun = Color(0xFFFFCD00)
        val Fall70 = Color(0xFFF8A763)
        val Fall = Color(0xFFF58220)
        val Red = Color(0xFFEE2F4E)
        val Pink = Color(0xFFC6007E)
        val LightGrey = Color(0xFFD9E1E2)
        val MiddleGrey = Color(0xFF768692)
        val DarkGrey = Color(0xFF333F48)
    }

    object Icons {

        @Suppress("CheckReturnValue")
        val Bluetooth: ImageVector
            get() {
                if (_bluetooth != null) {
                    return _bluetooth!!
                }
                _bluetooth =
                    ImageVector.Builder(
                        name = "bluetooth",
                        defaultWidth = 24.dp,
                        defaultHeight = 24.dp,
                        viewportWidth = 24f,
                        viewportHeight = 24f,
                    )
                        .apply {
                            path(
                                fill = SolidColor(Color.Black),
                                fillAlpha = 1f,
                                stroke = null,
                                strokeAlpha = 1f,
                                strokeLineWidth = 1f,
                                strokeLineCap = StrokeCap.Butt,
                                strokeLineJoin = StrokeJoin.Bevel,
                                strokeLineMiter = 1f,
                                pathFillType = PathFillType.Companion.NonZero,
                            ) {
                                moveTo(11f, 22f)
                                verticalLineTo(14.4f)
                                lineTo(6.4f, 19f)
                                lineTo(5f, 17.6f)
                                lineTo(10.6f, 12f)
                                lineTo(5f, 6.4f)
                                lineTo(6.4f, 5f)
                                lineTo(11f, 9.6f)
                                verticalLineTo(2f)
                                horizontalLineToRelative(1f)
                                lineToRelative(5.7f, 5.7f)
                                lineTo(13.4f, 12f)
                                lineToRelative(4.3f, 4.3f)
                                lineTo(12f, 22f)
                                horizontalLineTo(11f)
                                close()
                                moveTo(13f, 9.6f)
                                lineTo(14.9f, 7.7f)
                                lineTo(13f, 5.85f)
                                verticalLineTo(9.6f)
                                close()
                                moveToRelative(0f, 8.55f)
                                lineTo(14.9f, 16.3f)
                                lineTo(13f, 14.4f)
                                verticalLineToRelative(3.75f)
                                close()
                            }
                        }
                        .build()
                return _bluetooth!!
            }

        private var _bluetooth: ImageVector? = null

        @Suppress("CheckReturnValue")
        val Lock: ImageVector
            get() {
                if (_lock != null) {
                    return _lock!!
                }
                _lock =
                    ImageVector.Builder(
                        name = "lock",
                        defaultWidth = 24.dp,
                        defaultHeight = 24.dp,
                        viewportWidth = 24f,
                        viewportHeight = 24f,
                    )
                        .apply {
                            path(
                                fill = SolidColor(Color.Black),
                                fillAlpha = 1f,
                                stroke = null,
                                strokeAlpha = 1f,
                                strokeLineWidth = 1f,
                                strokeLineCap = StrokeCap.Butt,
                                strokeLineJoin = StrokeJoin.Bevel,
                                strokeLineMiter = 1f,
                                pathFillType = PathFillType.Companion.NonZero,
                            ) {
                                moveTo(6f, 22f)
                                quadTo(5.18f, 22f, 4.59f, 21.41f)
                                reflectiveQuadTo(4f, 20f)
                                verticalLineTo(10f)
                                quadTo(4f, 9.17f, 4.59f, 8.59f)
                                reflectiveQuadTo(6f, 8f)
                                horizontalLineTo(7f)
                                verticalLineTo(6f)
                                quadTo(7f, 3.92f, 8.46f, 2.46f)
                                reflectiveQuadTo(12f, 1f)
                                reflectiveQuadToRelative(3.54f, 1.46f)
                                reflectiveQuadTo(17f, 6f)
                                verticalLineTo(8f)
                                horizontalLineToRelative(1f)
                                quadToRelative(0.82f, 0f, 1.41f, 0.59f)
                                reflectiveQuadTo(20f, 10f)
                                verticalLineTo(20f)
                                quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                                reflectiveQuadTo(18f, 22f)
                                horizontalLineTo(6f)
                                close()
                                moveTo(6f, 20f)
                                horizontalLineTo(18f)
                                verticalLineTo(10f)
                                horizontalLineTo(6f)
                                verticalLineTo(20f)
                                close()
                                moveToRelative(7.41f, -3.59f)
                                quadTo(14f, 15.83f, 14f, 15f)
                                reflectiveQuadTo(13.41f, 13.59f)
                                reflectiveQuadTo(12f, 13f)
                                reflectiveQuadToRelative(-1.41f, 0.59f)
                                quadTo(10f, 14.18f, 10f, 15f)
                                reflectiveQuadToRelative(0.59f, 1.41f)
                                reflectiveQuadTo(12f, 17f)
                                reflectiveQuadToRelative(1.41f, -0.59f)
                                close()
                                moveTo(9f, 8f)
                                horizontalLineToRelative(6f)
                                verticalLineTo(6f)
                                quadTo(15f, 4.75f, 14.13f, 3.88f)
                                reflectiveQuadTo(12f, 3f)
                                reflectiveQuadTo(9.88f, 3.88f)
                                reflectiveQuadTo(9f, 6f)
                                verticalLineTo(8f)
                                close()
                                moveTo(6f, 20f)
                                verticalLineTo(10f)
                                verticalLineTo(20f)
                                close()
                            }
                        }
                        .build()
                return _lock!!
            }

        private var _lock: ImageVector? = null

        @Suppress("CheckReturnValue")
        val Encrypted: ImageVector
            get() {
                if (_encrypted != null) {
                    return _encrypted!!
                }
                _encrypted =
                    ImageVector.Builder(
                        name = "encrypted",
                        defaultWidth = 24.dp,
                        defaultHeight = 24.dp,
                        viewportWidth = 24f,
                        viewportHeight = 24f,
                    )
                        .apply {
                            path(
                                fill = SolidColor(Color.Black),
                                fillAlpha = 1f,
                                stroke = null,
                                strokeAlpha = 1f,
                                strokeLineWidth = 1f,
                                strokeLineCap = StrokeCap.Butt,
                                strokeLineJoin = StrokeJoin.Bevel,
                                strokeLineMiter = 1f,
                                pathFillType = PathFillType.Companion.NonZero,
                            ) {
                                moveTo(10.5f, 15f)
                                horizontalLineToRelative(3f)
                                lineTo(12.93f, 11.77f)
                                quadToRelative(0.5f, -0.25f, 0.79f, -0.72f)
                                reflectiveQuadTo(14f, 10f)
                                quadTo(14f, 9.17f, 13.41f, 8.59f)
                                reflectiveQuadTo(12f, 8f)
                                reflectiveQuadTo(10.59f, 8.59f)
                                reflectiveQuadTo(10f, 10f)
                                quadToRelative(0f, 0.57f, 0.29f, 1.05f)
                                reflectiveQuadToRelative(0.79f, 0.72f)
                                lineTo(10.5f, 15f)
                                close()
                                moveTo(12f, 22f)
                                quadTo(8.53f, 21.13f, 6.26f, 18.01f)
                                reflectiveQuadTo(4f, 11.1f)
                                verticalLineTo(5f)
                                lineTo(12f, 2f)
                                lineToRelative(8f, 3f)
                                verticalLineToRelative(6.1f)
                                quadToRelative(0f, 3.8f, -2.26f, 6.91f)
                                reflectiveQuadTo(12f, 22f)
                                close()
                                moveToRelative(0f, -2.1f)
                                quadToRelative(2.6f, -0.82f, 4.3f, -3.3f)
                                reflectiveQuadTo(18f, 11.1f)
                                verticalLineTo(6.38f)
                                lineTo(12f, 4.13f)
                                lineTo(6f, 6.38f)
                                verticalLineTo(11.1f)
                                quadToRelative(0f, 3.03f, 1.7f, 5.5f)
                                reflectiveQuadTo(12f, 19.9f)
                                close()
                                moveTo(12f, 12f)
                                close()
                            }
                        }
                        .build()
                return _encrypted!!
            }

        private var _encrypted: ImageVector? = null

        /** Material icon "wifi_tethering". */
        val Advertising: ImageVector by lazy {
            materialIcon(
                name = "advertising",
                pathData = "M12,11c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,13c0,-3.31 -2.69,-6 -6,-6s-6,2.69 -6,6c0,2.22 1.21,4.15 3,5.19l1,-1.74c-1.19,-0.7 -2,-1.97 -2,-3.45 0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,1.48 -0.81,2.75 -2,3.45l1,1.74c1.79,-1.04 3,-2.97 3,-5.19zM12,3C6.48,3 2,7.48 2,13c0,3.7 2.01,6.92 4.99,8.65l1,-1.73C5.61,18.53 4,15.96 4,13c0,-4.42 3.58,-8 8,-8s8,3.58 8,8c0,2.96 -1.61,5.53 -4,6.92l1,1.73c2.99,-1.73 5,-4.95 5,-8.65 0,-5.52 -4.48,-10 -10,-10z",
            )
        }

        /** Material icon "bluetooth_connected". */
        val BluetoothConnected: ImageVector by lazy {
            materialIcon(
                name = "bluetooth_connected",
                pathData = "M7,12l-2,-2 -2,2 2,2 2,-2zM17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41L11,22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59L13,5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88zM19,10l-2,2 2,2 2,-2 -2,-2z",
            )
        }

        /** Material icon "show_chart". */
        val Chart: ImageVector by lazy {
            materialIcon(
                name = "chart",
                pathData = "M3.5,18.49l6,-6.01 4,4L22,6.92l-1.41,-1.41 -7.09,7.97 -4,-4L2,16.99z",
            )
        }

        private fun materialIcon(name: String, pathData: String): ImageVector =
            ImageVector.Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
                .addPath(
                    pathData = PathParser().parsePathString(pathData).toNodes(),
                    fill = SolidColor(Color.Black),
                )
                .build()
    }
}

