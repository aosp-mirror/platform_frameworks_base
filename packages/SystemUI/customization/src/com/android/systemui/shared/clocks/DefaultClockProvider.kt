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
import android.graphics.Typeface
import android.view.LayoutInflater
import com.android.systemui.customization.R
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFontAxis
import com.android.systemui.plugins.clocks.ClockFontAxisSetting
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockMetadata
import com.android.systemui.plugins.clocks.ClockPickerConfig
import com.android.systemui.plugins.clocks.ClockProvider
import com.android.systemui.plugins.clocks.ClockSettings
import com.android.systemui.shared.clocks.view.HorizontalAlignment
import com.android.systemui.shared.clocks.view.VerticalAlignment

private val TAG = DefaultClockProvider::class.simpleName
const val DEFAULT_CLOCK_ID = "DEFAULT"

data class ClockContext(
    val context: Context,
    val resources: Resources,
    val settings: ClockSettings,
    val typefaceCache: TypefaceCache,
    val messageBuffers: ClockMessageBuffers,
    val messageBuffer: MessageBuffer,
)

/** Provides the default system clock */
class DefaultClockProvider(
    val ctx: Context,
    val layoutInflater: LayoutInflater,
    val resources: Resources,
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
            val buffers = messageBuffers ?: ClockMessageBuffers(LogUtil.DEFAULT_MESSAGE_BUFFER)
            val fontAxes = ClockFontAxis.merge(FlexClockController.FONT_AXES, settings.axes)
            val clockSettings = settings.copy(axes = fontAxes.map { it.toSetting() })
            val typefaceCache =
                TypefaceCache(buffers.infraMessageBuffer, NUM_CLOCK_FONT_ANIMATION_STEPS) {
                    FLEX_TYPEFACE
                }
            FlexClockController(
                ClockContext(
                    ctx,
                    resources,
                    clockSettings,
                    typefaceCache,
                    buffers,
                    buffers.infraMessageBuffer,
                ),
                FLEX_DESIGN,
            )
        } else {
            DefaultClockController(
                ctx,
                layoutInflater,
                resources,
                settings,
                migratedClocks,
                messageBuffers,
            )
        }
    }

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
        if (settings.clockId != DEFAULT_CLOCK_ID) {
            throw IllegalArgumentException("${settings.clockId} is unsupported by $TAG")
        }

        val fontAxes =
            if (!isClockReactiveVariantsEnabled) listOf()
            else ClockFontAxis.merge(FlexClockController.FONT_AXES, settings.axes)
        return ClockPickerConfig(
            DEFAULT_CLOCK_ID,
            resources.getString(R.string.clock_default_name),
            resources.getString(R.string.clock_default_description),
            resources.getDrawable(R.drawable.clock_default_thumbnail, null),
            isReactiveToTone = true,
            axes = fontAxes,
        )
    }

    companion object {
        const val NUM_CLOCK_FONT_ANIMATION_STEPS = 30

        // TODO(b/364681643): Variations for retargetted DIGITAL_CLOCK_FLEX
        val LEGACY_FLEX_LS_VARIATION =
            listOf(
                ClockFontAxisSetting("wght", 600f),
                ClockFontAxisSetting("wdth", 100f),
                ClockFontAxisSetting("ROND", 100f),
                ClockFontAxisSetting("slnt", 0f),
            )

        val LEGACY_FLEX_AOD_VARIATION =
            listOf(
                ClockFontAxisSetting("wght", 74f),
                ClockFontAxisSetting("wdth", 43f),
                ClockFontAxisSetting("ROND", 100f),
                ClockFontAxisSetting("slnt", 0f),
            )

        val FLEX_TYPEFACE by lazy {
            // TODO(b/364680873): Move constant to config_clockFontFamily when shipping
            Typeface.create("google-sans-flex-clock", Typeface.NORMAL)
        }

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
                                    style = FontTextStyle(lineHeight = 147.25f),
                                    aodStyle =
                                        FontTextStyle(
                                            fillColorLight = "#FFFFFFFF",
                                            outlineColor = "#00000000",
                                            renderType = RenderType.CHANGE_WEIGHT,
                                            transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                                            transitionDuration = 750,
                                        ),
                                    alignment =
                                        DigitalAlignment(
                                            HorizontalAlignment.CENTER,
                                            VerticalAlignment.BASELINE,
                                        ),
                                    dateTimeFormat = "hh",
                                ),
                                DigitalHandLayer(
                                    layerBounds = LayerBounds.FIT,
                                    timespec = DigitalTimespec.SECOND_DIGIT,
                                    style = FontTextStyle(lineHeight = 147.25f),
                                    aodStyle =
                                        FontTextStyle(
                                            fillColorLight = "#FFFFFFFF",
                                            outlineColor = "#00000000",
                                            renderType = RenderType.CHANGE_WEIGHT,
                                            transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                                            transitionDuration = 750,
                                        ),
                                    alignment =
                                        DigitalAlignment(
                                            HorizontalAlignment.CENTER,
                                            VerticalAlignment.BASELINE,
                                        ),
                                    dateTimeFormat = "hh",
                                ),
                                DigitalHandLayer(
                                    layerBounds = LayerBounds.FIT,
                                    timespec = DigitalTimespec.FIRST_DIGIT,
                                    style = FontTextStyle(lineHeight = 147.25f),
                                    aodStyle =
                                        FontTextStyle(
                                            fillColorLight = "#FFFFFFFF",
                                            outlineColor = "#00000000",
                                            renderType = RenderType.CHANGE_WEIGHT,
                                            transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                                            transitionDuration = 750,
                                        ),
                                    alignment =
                                        DigitalAlignment(
                                            HorizontalAlignment.CENTER,
                                            VerticalAlignment.BASELINE,
                                        ),
                                    dateTimeFormat = "mm",
                                ),
                                DigitalHandLayer(
                                    layerBounds = LayerBounds.FIT,
                                    timespec = DigitalTimespec.SECOND_DIGIT,
                                    style = FontTextStyle(lineHeight = 147.25f),
                                    aodStyle =
                                        FontTextStyle(
                                            fillColorLight = "#FFFFFFFF",
                                            outlineColor = "#00000000",
                                            renderType = RenderType.CHANGE_WEIGHT,
                                            transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                                            transitionDuration = 750,
                                        ),
                                    alignment =
                                        DigitalAlignment(
                                            HorizontalAlignment.CENTER,
                                            VerticalAlignment.BASELINE,
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
                        style = FontTextStyle(fontSizeScale = 0.98f),
                        aodStyle =
                            FontTextStyle(
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
