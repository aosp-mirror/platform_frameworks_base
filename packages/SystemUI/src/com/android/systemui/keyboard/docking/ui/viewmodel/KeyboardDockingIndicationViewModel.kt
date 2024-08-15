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

package com.android.systemui.keyboard.docking.ui.viewmodel

import android.content.Context
import android.view.Surface
import android.view.WindowManager
import com.android.settingslib.Utils
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.docking.domain.interactor.KeyboardDockingIndicationInteractor
import com.android.systemui.surfaceeffects.glowboxeffect.GlowBoxConfig
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SysUISingleton
class KeyboardDockingIndicationViewModel
@Inject
constructor(
    private val windowManager: WindowManager,
    private val context: Context,
    keyboardDockingIndicationInteractor: KeyboardDockingIndicationInteractor,
    configurationInteractor: ConfigurationInteractor,
    @Background private val backgroundScope: CoroutineScope,
) {

    private val _edgeGlow: MutableStateFlow<GlowBoxConfig> = MutableStateFlow(createEffectConfig())
    val edgeGlow = _edgeGlow.asStateFlow()
    val keyboardConnected = keyboardDockingIndicationInteractor.onKeyboardConnected

    init {
        /**
         * Expected behaviors:
         * 1) On keyboard docking event, we play the animation for a fixed duration.
         * 2) If the keyboard gets disconnected during the animation, we finish the animation with
         *    ease out.
         * 3) If the configuration changes (e.g., device rotation), we force cancel the animation
         *    with no ease out.
         */
        backgroundScope.launch {
            configurationInteractor.onAnyConfigurationChange.collect {
                _edgeGlow.value = createEffectConfig()
            }
        }
    }

    private fun createEffectConfig(): GlowBoxConfig {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()

        val startCenterX: Float
        val startCenterY: Float
        val endCenterX: Float
        val endCenterY: Float
        val boxWidth: Float
        val boxHeight: Float

        when (context.display.rotation) {
            Surface.ROTATION_0 -> {
                endCenterX = width
                endCenterY = height * 0.5f
                startCenterX = endCenterX + OFFSET
                startCenterY = endCenterY
                boxWidth = THICKNESS
                boxHeight = height
            }
            Surface.ROTATION_90 -> {
                endCenterX = width * 0.5f
                endCenterY = 0f
                startCenterX = endCenterX
                startCenterY = endCenterY - OFFSET
                boxWidth = width
                boxHeight = THICKNESS
            }
            Surface.ROTATION_180 -> {
                endCenterX = 0f
                endCenterY = height * 0.5f
                startCenterX = endCenterX - OFFSET
                startCenterY = endCenterY
                boxWidth = THICKNESS
                boxHeight = height
            }
            Surface.ROTATION_270 -> {
                endCenterX = width * 0.5f
                endCenterY = height
                startCenterX = endCenterX
                startCenterY = endCenterY + OFFSET
                boxWidth = width
                boxHeight = THICKNESS
            }
            else -> { // Shouldn't happen. Just fall off to ROTATION_0
                endCenterX = width
                endCenterY = height * 0.5f
                startCenterX = endCenterX + OFFSET
                startCenterY = endCenterY
                boxWidth = THICKNESS
                boxHeight = height
            }
        }

        return GlowBoxConfig(
            startCenterX = startCenterX,
            startCenterY = startCenterY,
            endCenterX = endCenterX,
            endCenterY = endCenterY,
            width = boxWidth,
            height = boxHeight,
            color = Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor,
            blurAmount = BLUR_AMOUNT,
            duration = DURATION,
            easeInDuration = EASE_DURATION,
            easeOutDuration = EASE_DURATION
        )
    }

    private companion object {
        private const val OFFSET = 300f
        private const val THICKNESS = 20f
        private const val BLUR_AMOUNT = 700f
        private const val DURATION = 3000L
        private const val EASE_DURATION = 800L
    }
}
