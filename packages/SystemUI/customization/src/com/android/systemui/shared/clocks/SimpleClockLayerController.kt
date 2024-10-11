/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shared.clocks

import android.content.Context
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.clocks.ClockAnimations
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceEvents
import com.android.systemui.shared.clocks.view.SimpleDigitalClockTextView
import kotlin.reflect.KClass

typealias LayerControllerConstructor =
    (
        ctx: Context,
        assets: AssetLoader,
        layer: ClockLayer,
        isLargeClock: Boolean,
        messageBuffer: MessageBuffer,
    ) -> SimpleClockLayerController

interface SimpleClockLayerController {
    val view: View
    val events: ClockEvents
    val animations: ClockAnimations
    val faceEvents: ClockFaceEvents
    val config: ClockFaceConfig

    @VisibleForTesting var fakeTimeMills: Long?

    // Called immediately after either onColorPaletteChanged or onSeedColorChanged is called.
    // Provided for convience to not duplicate color update logic after state updated.
    fun updateColors() {}

    companion object Factory {
        val constructorMap = mutableMapOf<Pair<KClass<*>, KClass<*>?>, LayerControllerConstructor>()

        internal inline fun <reified TLayer> registerConstructor(
            noinline constructor: LayerControllerConstructor,
        ) where TLayer : ClockLayer {
            constructorMap[Pair(TLayer::class, null)] = constructor
        }

        inline fun <reified TLayer, reified TStyle> registerTextConstructor(
            noinline constructor: LayerControllerConstructor,
        ) where TLayer : ClockLayer, TStyle : TextStyle {
            constructorMap[Pair(TLayer::class, TStyle::class)] = constructor
        }

        init {
            registerConstructor<ComposedDigitalHandLayer>(::ComposedDigitalLayerController)
            registerTextConstructor<DigitalHandLayer, FontTextStyle>(::createSimpleDigitalLayer)
        }

        private fun createSimpleDigitalLayer(
            ctx: Context,
            assets: AssetLoader,
            layer: ClockLayer,
            isLargeClock: Boolean,
            messageBuffer: MessageBuffer
        ): SimpleClockLayerController {
            val view = SimpleDigitalClockTextView(ctx, messageBuffer)
            return SimpleDigitalHandLayerController(
                ctx,
                assets,
                layer as DigitalHandLayer,
                view,
                messageBuffer
            )
        }

        fun create(
            ctx: Context,
            assets: AssetLoader,
            layer: ClockLayer,
            isLargeClock: Boolean,
            messageBuffer: MessageBuffer
        ): SimpleClockLayerController {
            val styleClass = if (layer is DigitalHandLayer) layer.style::class else null
            val key = Pair(layer::class, styleClass)
            return constructorMap[key]?.invoke(ctx, assets, layer, isLargeClock, messageBuffer)
                ?: throw IllegalArgumentException("Unrecognized ClockLayer type: $key")
        }
    }
}
