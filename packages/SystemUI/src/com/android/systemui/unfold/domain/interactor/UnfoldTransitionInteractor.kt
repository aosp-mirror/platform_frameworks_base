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
 * limitations under the License
 */
package com.android.systemui.unfold.domain.interactor

import android.view.View
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.unfold.data.repository.UnfoldTransitionRepository
import com.android.systemui.unfold.data.repository.UnfoldTransitionStatus.TransitionFinished
import com.android.systemui.unfold.data.repository.UnfoldTransitionStatus.TransitionInProgress
import com.android.systemui.unfold.data.repository.UnfoldTransitionStatus.TransitionStarted
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Contains business-logic related to fold-unfold transitions while interacting with
 * [UnfoldTransitionRepository]
 */
@SysUISingleton
class UnfoldTransitionInteractor
@Inject
constructor(
    private val repository: UnfoldTransitionRepository,
    private val configurationInteractor: ConfigurationInteractor,
) {
    /** Returns availability of fold/unfold transitions on the device */
    val isAvailable: Boolean
        get() = repository.isAvailable

    /**
     * This mapping emits 1 when the device is completely unfolded and 0.0 when the device is
     * completely folded.
     */
    private val unfoldProgress: Flow<Float> =
        repository.transitionStatus
            .map { (it as? TransitionInProgress)?.progress ?: 1f }
            .onStart { emit(1f) }
            .distinctUntilChanged()

    /**
     * Amount of X-axis translation to apply to various elements as the unfolded foldable is folded
     * slightly, in pixels.
     *
     * @param isOnStartSide Whether the consumer wishes to get a translation amount that's suitable
     *   for an element that's on the start-side (left hand-side in left-to-right layouts); if
     *   `true`, the values will provide positive translations to push the left-hand-side element
     *   towards the foldable hinge; if `false`, the values will be inverted to provide negative
     *   translations to push the right-hand-side element towards the foldable hinge. Note that this
     *   method already accounts for left-to-right vs. right-to-left layout directions.
     */
    fun unfoldTranslationX(isOnStartSide: Boolean): Flow<Float> {
        return combine(
            unfoldProgress,
            configurationInteractor.dimensionPixelSize(R.dimen.notification_side_paddings),
            configurationInteractor.layoutDirection.map {
                if (it == View.LAYOUT_DIRECTION_RTL) -1 else 1
            },
        ) { unfoldedAmount, max, layoutDirectionMultiplier ->
            val sideMultiplier = if (isOnStartSide) 1 else -1
            max * (1 - unfoldedAmount) * sideMultiplier * layoutDirectionMultiplier
        }
    }

    /** Suspends and waits for a fold/unfold transition to finish */
    suspend fun waitForTransitionFinish() {
        repository.transitionStatus.filter { it is TransitionFinished }.first()
    }

    /** Suspends and waits for a fold/unfold transition to start */
    suspend fun waitForTransitionStart() {
        repository.transitionStatus.filter { it is TransitionStarted }.first()
    }
}
