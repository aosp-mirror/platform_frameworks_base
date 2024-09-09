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

package com.android.systemui.shade.domain.interactor

import androidx.annotation.FloatRange
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Defines interface for classes that can provide state and business logic related to the mode of
 * the shade.
 */
interface ShadeModeInteractor {

    /**
     * The version of the shade layout to use.
     *
     * Note: Most likely, you want to read [isShadeLayoutWide] instead of this.
     */
    val shadeMode: StateFlow<ShadeMode>

    /**
     * Whether the shade layout should be wide (true) or narrow (false).
     *
     * In a wide layout, notifications and quick settings each take up only half the screen width
     * (whether they are shown at the same time or not). In a narrow layout, they can each be as
     * wide as the entire screen.
     */
    val isShadeLayoutWide: StateFlow<Boolean>

    /** Convenience shortcut for querying whether the current [shadeMode] is [ShadeMode.Dual]. */
    val isDualShade: Boolean
        get() = shadeMode.value is ShadeMode.Dual

    /**
     * The fraction between [0..1] (i.e., percentage) of screen width to consider the threshold
     * between "top-left" and "top-right" for the purposes of dual-shade invocation.
     *
     * When the dual-shade is not wide, this always returns 0.5 (the top edge is evenly split). On
     * wide layouts however, a larger fraction is returned because only the area of the system
     * status icons is considered top-right.
     *
     * Note that this fraction only determines the split between the absolute left and right
     * directions. In RTL layouts, the "top-start" edge will resolve to "top-right", and "top-end"
     * will resolve to "top-left".
     */
    @FloatRange(from = 0.0, to = 1.0) fun getTopEdgeSplitFraction(): Float
}

class ShadeModeInteractorImpl
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val repository: ShadeRepository,
) : ShadeModeInteractor {

    override val isShadeLayoutWide: StateFlow<Boolean> = repository.isShadeLayoutWide

    override val shadeMode: StateFlow<ShadeMode> =
        isShadeLayoutWide
            .map(this::determineShadeMode)
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                initialValue = determineShadeMode(isShadeLayoutWide.value),
            )

    @FloatRange(from = 0.0, to = 1.0)
    override fun getTopEdgeSplitFraction(): Float {
        // Note: this implicitly relies on isShadeLayoutWide being hot (i.e. collected). This
        // assumption allows us to query its value on demand (during swipe source detection) instead
        // of running another infinite coroutine.
        // TODO(b/338577208): Instead of being fixed at 0.8f, this should dynamically updated based
        //  on the position of system-status icons in the status bar.
        return if (repository.isShadeLayoutWide.value) 0.8f else 0.5f
    }

    private fun determineShadeMode(isShadeLayoutWide: Boolean): ShadeMode {
        return when {
            DualShade.isEnabled -> ShadeMode.Dual
            isShadeLayoutWide -> ShadeMode.Split
            else -> ShadeMode.Single
        }
    }
}

class ShadeModeInteractorEmptyImpl @Inject constructor() : ShadeModeInteractor {

    override val shadeMode: StateFlow<ShadeMode> = MutableStateFlow(ShadeMode.Single)

    override val isShadeLayoutWide: StateFlow<Boolean> = MutableStateFlow(false)

    override fun getTopEdgeSplitFraction(): Float = 0.5f
}
