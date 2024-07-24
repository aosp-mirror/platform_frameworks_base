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

package com.android.systemui.keyguard.ui.composable.blueprint

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.plugins.clocks.ClockController
import kotlin.math.min
import kotlin.math.roundToInt

/** Produces a [BurnInState] that can be used to query the `LockscreenBurnInViewModel` flows. */
@Composable
fun rememberBurnIn(
    clockInteractor: KeyguardClockInteractor,
): BurnInState {
    val clock by clockInteractor.currentClock.collectAsState()

    val (smartspaceTop, onSmartspaceTopChanged) = remember { mutableStateOf<Float?>(null) }
    val (smallClockTop, onSmallClockTopChanged) = remember { mutableStateOf<Float?>(null) }

    val topmostTop =
        when {
            smartspaceTop != null && smallClockTop != null -> min(smartspaceTop, smallClockTop)
            smartspaceTop != null -> smartspaceTop
            smallClockTop != null -> smallClockTop
            else -> 0f
        }.roundToInt()

    val params = rememberBurnInParameters(clock, topmostTop)

    return remember(params, onSmartspaceTopChanged, onSmallClockTopChanged) {
        BurnInState(
            parameters = params,
            onSmartspaceTopChanged = onSmartspaceTopChanged,
            onSmallClockTopChanged = onSmallClockTopChanged,
        )
    }
}

@Composable
private fun rememberBurnInParameters(
    clock: ClockController?,
    topmostTop: Int,
): BurnInParameters {
    val density = LocalDensity.current
    val topInset = WindowInsets.systemBars.union(WindowInsets.displayCutout).getTop(density)

    return remember(clock, topInset, topmostTop) {
        BurnInParameters(
            clockControllerProvider = { clock },
            topInset = topInset,
            minViewY = topmostTop,
        )
    }
}

data class BurnInState(
    /** Parameters for use with the `LockscreenBurnInViewModel. */
    val parameters: BurnInParameters,
    /**
     * Callback to invoke when the top coordinate of the smartspace element is updated, pass `null`
     * when the element is not shown.
     */
    val onSmartspaceTopChanged: (Float?) -> Unit,
    /**
     * Callback to invoke when the top coordinate of the small clock element is updated, pass `null`
     * when the element is not shown.
     */
    val onSmallClockTopChanged: (Float?) -> Unit,
)
