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
package com.android.systemui.unfold.data.repository

import androidx.annotation.FloatRange
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.data.repository.UnfoldTransitionStatus.TransitionFinished
import com.android.systemui.unfold.data.repository.UnfoldTransitionStatus.TransitionInProgress
import com.android.systemui.unfold.data.repository.UnfoldTransitionStatus.TransitionStarted
import com.android.systemui.util.kotlin.getOrNull
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Repository for fold/unfold transitions */
interface UnfoldTransitionRepository {
    /** Returns false if fold/unfold transitions are not available on this device */
    val isAvailable: Boolean

    /**
     * Emits current transition state on each transition change such as transition start or finish
     * [UnfoldTransitionStatus]
     */
    val transitionStatus: Flow<UnfoldTransitionStatus>
}

/** Transition event of fold/unfold transition */
sealed class UnfoldTransitionStatus {
    /** Status that is sent when fold or unfold transition is in started state */
    data object TransitionStarted : UnfoldTransitionStatus()
    /** Status that is sent while fold or unfold transition is in progress */
    data class TransitionInProgress(
        @FloatRange(from = 0.0, to = 1.0) val progress: Float,
    ) : UnfoldTransitionStatus()
    /** Status that is sent when fold or unfold transition is finished */
    data object TransitionFinished : UnfoldTransitionStatus()
}

class UnfoldTransitionRepositoryImpl
@Inject
constructor(
    private val unfoldProgressProvider: Optional<UnfoldTransitionProgressProvider>,
) : UnfoldTransitionRepository {

    override val isAvailable: Boolean
        get() = unfoldProgressProvider.isPresent

    override val transitionStatus: Flow<UnfoldTransitionStatus>
        get() {
            val provider = unfoldProgressProvider.getOrNull() ?: return emptyFlow()

            return conflatedCallbackFlow {
                val callback =
                    object : UnfoldTransitionProgressProvider.TransitionProgressListener {
                        override fun onTransitionStarted() {
                            trySend(TransitionStarted)
                        }

                        override fun onTransitionProgress(progress: Float) {
                            trySend(TransitionInProgress(progress))
                        }

                        override fun onTransitionFinished() {
                            trySend(TransitionFinished)
                        }
                    }
                provider.addCallback(callback)
                awaitClose { provider.removeCallback(callback) }
            }
        }
}
