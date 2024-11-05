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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.keyguard.domain.interactor.LightRevealScrimInteractor
import com.android.systemui.statusbar.LightRevealEffect
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Models UI state for the light reveal scrim, which is used during screen on and off animations to
 * draw a gradient that reveals/hides the contents of the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightRevealScrimViewModel
@Inject
constructor(private val interactor: LightRevealScrimInteractor) {
    val lightRevealEffect: Flow<LightRevealEffect> = interactor.lightRevealEffect
    val revealAmount: Flow<Float> = interactor.revealAmount

    /** Max alpha for the scrim + whether to animate the change */
    val maxAlpha: Flow<Pair<Float, Boolean>> =
        interactor.maxAlpha.map { alpha ->
            Pair(
                alpha,
                // Darken immediately if going to be fully opaque
                if (alpha == 1f) false else true,
            )
        }

    fun setWallpaperSupportsAmbientMode(supportsAmbientMode: Boolean) {
        interactor.setWallpaperSupportsAmbientMode(supportsAmbientMode)
    }
}
