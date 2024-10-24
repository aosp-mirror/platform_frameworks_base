/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.clocks

import android.content.Context
import android.content.res.Resources
import android.view.LayoutInflater
import com.android.systemui.customization.R
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogcatOnlyMessageBuffer
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockId
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockMetadata
import com.android.systemui.plugins.clocks.ClockPickerConfig
import com.android.systemui.plugins.clocks.ClockProvider
import com.android.systemui.plugins.clocks.ClockSettings
import com.android.systemui.shared.clocks.view.HorizontalAlignment
import com.android.systemui.shared.clocks.view.VerticalAlignment

private val TAG = DefaultClockProvider::class.simpleName
const val DEFAULT_CLOCK_ID = "DEFAULT"

/** Provides the default system clock */
class DefaultClockProvider(
    val ctx: Context,
    val layoutInflater: LayoutInflater,
    val resources: Resources,
    private val hasStepClockAnimation: Boolean = false,
    private val migratedClocks: Boolean = false,
    private val isClockReactiveVariantsEnabled: Boolean = false,
) : ClockProvider {
    private var messageBuffers: ClockMessageBuffers? = null

    override fun initialize(buffers: ClockMessageBuffers?) {
        messageBuffers = buffers
    }

    override fun getClocks(): List<ClockMetadata> = listOf(ClockMetadata(DEFAULT_CLOCK_ID))

    override fun createClock(settings: ClockSettings): ClockController {
        if (settings.clockId != DEFAULT_CLOCK_ID) {
            throw IllegalArgumentException("${settings.clockId} is unsupported by $TAG")
        }

        return if (isClockReactiveVariantsEnabled) {
            val buffer =
                messageBuffers?.infraMessageBuffer ?: LogcatOnlyMessageBuffer(LogLevel.INFO)
            val assets = AssetLoader(ctx, ctx, "clocks/", buffer)
            assets.setSeedColor(settings.seedColor, null)
            FlexClockController(ctx, resources, assets, FLEX_DESIGN, messageBuffers)
        } else {
            DefaultClockController(
                ctx,
                layoutInflater,
                resources,
                settings,
                hasStepClockAnimation,
                migratedClocks,
                messageBuffers,
            )
        }
    }

    override fun getClockPickerConfig(id: ClockId): ClockPickerConfig {
        if (id != DEFAULT_CLOCK_ID) {
            throw IllegalArgumentException("$id is unsupported by $TAG")
        }

        return ClockPickerConfig(
            DEFAULT_CLOCK_ID,
            resources.getString(R.string.clock_default_name),
            resources.getString(R.string.clock_default_description),
            // TODO(b/352049256): Update placeholder to actual resource
            resources.getDrawable(R.drawable.clock_default_thumbnail, null),
            isReactiveToTone = true,
            isReactiveToTouch = isClockReactiveVariantsEnabled,
            axes = listOf(), // TODO: Ater some picker definition
        )
    }

    companion object {
        val FLEX_DESIGN = run {
            val largeLayer =
                listOf(
                    ComposedDigitalHandLayer(
                        layerBounds = LayerBounds.FIT,
                        customizedView = "FlexClockView",
                        digitalLayers =
                            listOf(
                                DigitalHandLayer(
                                    layerBounds = LayerBounds.FIT,
                                    timespec = DigitalTimespec.FIRST_DIGIT,
                                    style =
                                        FontTextStyle(
                                            lineHeight = 147.25f,
                                            fontVariation =
                                                "'wght' 603, 'wdth' 100, 'opsz' 144, 'ROND' 100",
                                        ),
                                    aodStyle =
                                        FontTextStyle(
                                            fontVariation =
                                                "'wght' 74, 'wdth' 43, 'opsz' 144, 'ROND' 100",
                                            fillColorLight = "#FFFFFFFF",
                                            outlineColor = "#00000000",
                                            renderType = RenderType.CHANGE_WEIGHT,
                                            transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                                            transitionDuration = 750,
                                        ),
                                    alignment =
                                        DigitalAlignment(
                                            HorizontalAlignment.CENTER,
                                            VerticalAlignment.CENTER,
                                        ),
                                    dateTimeFormat = "hh",
                                ),
                                DigitalHandLayer(
                                    layerBounds = LayerBounds.FIT,
                                    timespec = DigitalTimespec.SECOND_DIGIT,
                                    style =
                                        FontTextStyle(
                                            lineHeight = 147.25f,
                                            fontVariation =
                                                "'wght' 603, 'wdth' 100, 'opsz' 144, 'ROND' 100",
                                        ),
                                    aodStyle =
                                        FontTextStyle(
                                            fontVariation =
                                                "'wght' 74, 'wdth' 43, 'opsz' 144, 'ROND' 100",
                                            fillColorLight = "#FFFFFFFF",
                                            outlineColor = "#00000000",
                                            renderType = RenderType.CHANGE_WEIGHT,
                                            transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                                            transitionDuration = 750,
                                        ),
                                    alignment =
                                        DigitalAlignment(
                                            HorizontalAlignment.CENTER,
                                            VerticalAlignment.CENTER,
                                        ),
                                    dateTimeFormat = "hh",
                                ),
                                DigitalHandLayer(
                                    layerBounds = LayerBounds.FIT,
                                    timespec = DigitalTimespec.FIRST_DIGIT,
                                    style =
                                        FontTextStyle(
                                            lineHeight = 147.25f,
                                            fontVariation =
                                                "'wght' 603, 'wdth' 100, 'opsz' 144, 'ROND' 100",
                                        ),
                                    aodStyle =
                                        FontTextStyle(
                                            fontVariation =
                                                "'wght' 74, 'wdth' 43, 'opsz' 144, 'ROND' 100",
                                            fillColorLight = "#FFFFFFFF",
                                            outlineColor = "#00000000",
                                            renderType = RenderType.CHANGE_WEIGHT,
                                            transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                                            transitionDuration = 750,
                                        ),
                                    alignment =
                                        DigitalAlignment(
                                            HorizontalAlignment.CENTER,
                                            VerticalAlignment.CENTER,
                                        ),
                                    dateTimeFormat = "mm",
                                ),
                                DigitalHandLayer(
                                    layerBounds = LayerBounds.FIT,
                                    timespec = DigitalTimespec.SECOND_DIGIT,
                                    style =
                                        FontTextStyle(
                                            lineHeight = 147.25f,
                                            fontVariation =
                                                "'wght' 603, 'wdth' 100, 'opsz' 144, 'ROND' 100",
                                        ),
                                    aodStyle =
                                        FontTextStyle(
                                            fontVariation =
                                                "'wght' 74, 'wdth' 43, 'opsz' 144, 'ROND' 100",
                                            fillColorLight = "#FFFFFFFF",
                                            outlineColor = "#00000000",
                                            renderType = RenderType.CHANGE_WEIGHT,
                                            transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                                            transitionDuration = 750,
                                        ),
                                    alignment =
                                        DigitalAlignment(
                                            HorizontalAlignment.CENTER,
                                            VerticalAlignment.CENTER,
                                        ),
                                    dateTimeFormat = "mm",
                                ),
                            ),
                    )
                )

            val smallLayer =
                listOf(
                    DigitalHandLayer(
                        layerBounds = LayerBounds.FIT,
                        timespec = DigitalTimespec.TIME_FULL_FORMAT,
                        style =
                            FontTextStyle(
                                fontVariation = "'wght' 600, 'wdth' 100, 'opsz' 144, 'ROND' 100",
                                fontSizeScale = 0.98f,
                            ),
                        aodStyle =
                            FontTextStyle(
                                fontVariation = "'wght' 133, 'wdth' 43, 'opsz' 144, 'ROND' 100",
                                fillColorLight = "#FFFFFFFF",
                                outlineColor = "#00000000",
                                renderType = RenderType.CHANGE_WEIGHT,
                            ),
                        alignment = DigitalAlignment(HorizontalAlignment.LEFT, null),
                        dateTimeFormat = "h:mm",
                    )
                )

            ClockDesign(
                id = DEFAULT_CLOCK_ID,
                name = "@string/clock_default_name",
                description = "@string/clock_default_description",
                large = ClockFace(layers = largeLayer),
                small = ClockFace(layers = smallLayer),
            )
        }
    }
}
