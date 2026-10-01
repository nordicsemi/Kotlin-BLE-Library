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
    }
}

