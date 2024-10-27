/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.binder

import android.animation.ValueAnimator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators.ALPHA_IN
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.keyguard.ui.viewmodel.LightRevealScrimViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.shared.Flags.ambientAod
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.wallpapers.ui.viewmodel.WallpaperViewModel
import com.android.app.tracing.coroutines.launchTraced as launch

object LightRevealScrimViewBinder {
    @JvmStatic
    fun bind(
        revealScrim: LightRevealScrim,
        viewModel: LightRevealScrimViewModel,
        wallpaperViewModel: WallpaperViewModel,
    ) {
        revealScrim.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                if (ambientAod()) {
                    launch("$TAG#wallpaperViewModel.wallpaperSupportsAmbientMode") {
                        wallpaperViewModel.wallpaperSupportsAmbientMode.collect {
                            viewModel.setWallpaperSupportsAmbientMode(it)
                        }
                    }
                    launch("$TAG#viewModel.maxAlpha") {
                        var animator: ValueAnimator? = null
                        viewModel.maxAlpha.collect { (alpha, animate) ->
                            if (alpha != revealScrim.alpha) {
                                animator?.cancel()
                                if (!animate) {
                                    revealScrim.alpha = alpha
                                } else {
                                    animator =
                                        ValueAnimator.ofFloat(revealScrim.alpha, alpha).apply {
                                            startDelay = 333
                                            duration = 733
                                            interpolator = ALPHA_IN
                                            addUpdateListener { animation ->
                                                revealScrim.alpha =
                                                    animation.getAnimatedValue() as Float
                                            }
                                            start()
                                        }
                                }
                            }
                        }
                    }
                }

                launch("$TAG#viewModel.revealAmount") {
                    viewModel.revealAmount.collect { revealScrim.revealAmount = it }
                }

                launch("$TAG#viewModel.lightRevealEffect") {
                    viewModel.lightRevealEffect.collect { effect ->
                        revealScrim.revealEffect = effect
                    }
                }
            }
        }
    }

    private const val TAG = "LightRevealScrimViewBinder"
}
