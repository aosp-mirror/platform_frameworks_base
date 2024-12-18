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

package com.android.systemui.keyboard.docking.binder

import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.WindowManager
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.docking.ui.KeyboardDockingIndicationView
import com.android.systemui.keyboard.docking.ui.viewmodel.KeyboardDockingIndicationViewModel
import com.android.systemui.surfaceeffects.PaintDrawCallback
import com.android.systemui.surfaceeffects.glowboxeffect.GlowBoxEffect
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class KeyboardDockingIndicationViewBinder
@Inject
constructor(
    context: Context,
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: KeyboardDockingIndicationViewModel,
    private val windowManager: ViewCaptureAwareWindowManager,
) {

    private val windowLayoutParams =
        WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.TRANSLUCENT
            type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
            fitInsetsTypes = 0 // Ignore insets from all system bars
            title = "Edge glow effect"
            flags =
                (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            setTrustedOverlay()
        }

    private var glowEffect: GlowBoxEffect? = null
    private val glowEffectView = KeyboardDockingIndicationView(context, null)

    private val drawCallback =
        object : PaintDrawCallback {
            override fun onDraw(paint: Paint) {
                glowEffectView.draw(paint)
            }
        }

    private val stateChangedCallback =
        object : GlowBoxEffect.AnimationStateChangedCallback {
            override fun onStart() {
                windowManager.addView(glowEffectView, windowLayoutParams)
            }

            override fun onEnd() {
                windowManager.removeView(glowEffectView)
            }
        }

    fun startListening() {
        applicationScope.launch {
            viewModel.edgeGlow.collect { config ->
                if (glowEffect == null) {
                    glowEffect = GlowBoxEffect(config, drawCallback, stateChangedCallback)
                } else {
                    glowEffect?.finish(force = true)
                    glowEffect!!.updateConfig(config)
                }
            }
        }

        applicationScope.launch {
            viewModel.keyboardConnected.collect { connected ->
                if (connected) {
                    glowEffect?.play()
                } else {
                    glowEffect?.finish()
                }
            }
        }
    }
}
