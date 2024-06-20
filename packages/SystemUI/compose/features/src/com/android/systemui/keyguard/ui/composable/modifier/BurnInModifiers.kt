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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.BurnInScaleViewModel
import kotlinx.coroutines.flow.map

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
    val translationYState = remember { mutableStateOf(0F) }
    val copiedParams = params.copy(translationY = { translationYState.value })
    val burnIn = viewModel.movement(copiedParams)
    val translationX by
        burnIn.map { it.translationX.toFloat() }.collectAsStateWithLifecycle(initialValue = 0f)
    val translationY by
        burnIn.map { it.translationY.toFloat() }.collectAsStateWithLifecycle(initialValue = 0f)
    translationYState.value = translationY
    val scaleViewModel by
        burnIn
            .map {
                BurnInScaleViewModel(
                    scale = it.scale,
                    scaleClockOnly = it.scaleClockOnly,
                )
            }
            .collectAsStateWithLifecycle(initialValue = BurnInScaleViewModel())

    return this.graphicsLayer {
        this.translationX = if (isClock) 0F else translationX
        this.translationY = translationY
        this.alpha = alpha

        val scale = if (scaleViewModel.scaleClockOnly) scaleViewModel.scale else 1f
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
