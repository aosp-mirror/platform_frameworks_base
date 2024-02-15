/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.composable.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.BurnInScaleViewModel

/**
 * Modifies the composable to account for anti-burn in translation, alpha, and scaling.
 *
 * Please override [isClock] as `true` if the composable is an element that's part of a clock.
 */
@Composable
fun Modifier.burnInAware(
    viewModel: AodBurnInViewModel,
    params: BurnInParameters,
    isClock: Boolean = false,
): Modifier {
    val translationX by viewModel.translationX(params).collectAsState(initial = 0f)
    val translationY by viewModel.translationY(params).collectAsState(initial = 0f)
    val scaleViewModel by viewModel.scale(params).collectAsState(initial = BurnInScaleViewModel())

    return this.graphicsLayer {
        val scale =
            when {
                scaleViewModel.scaleClockOnly && isClock -> scaleViewModel.scale
                !scaleViewModel.scaleClockOnly -> scaleViewModel.scale
                else -> 1f
            }

        this.translationX = translationX
        this.translationY = translationY
        this.alpha = alpha
        this.scaleX = scale
        this.scaleY = scale
    }
}

/** Reports the "top" coordinate of the modified composable to the given [consumer]. */
@Composable
fun Modifier.onTopPlacementChanged(
    consumer: (Float) -> Unit,
): Modifier {
    return onPlaced { coordinates -> consumer(coordinates.boundsInWindow().top) }
}
