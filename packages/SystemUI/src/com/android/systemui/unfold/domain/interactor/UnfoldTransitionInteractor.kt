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

import com.android.systemui.unfold.data.repository.UnfoldTransitionRepository
import com.android.systemui.unfold.data.repository.UnfoldTransitionStatus.TransitionFinished
import com.android.systemui.unfold.data.repository.UnfoldTransitionStatus.TransitionStarted
import javax.inject.Inject
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * Contains business-logic related to fold-unfold transitions while interacting with
 * [UnfoldTransitionRepository]
 */
interface UnfoldTransitionInteractor {
    /** Returns availability of fold/unfold transitions on the device */
    val isAvailable: Boolean

    /** Suspends and waits for a fold/unfold transition to finish */
    suspend fun waitForTransitionFinish()

    /** Suspends and waits for a fold/unfold transition to start */
    suspend fun waitForTransitionStart()
}

class UnfoldTransitionInteractorImpl
@Inject
constructor(private val repository: UnfoldTransitionRepository) : UnfoldTransitionInteractor {

    override val isAvailable: Boolean
        get() = repository.isAvailable

    override suspend fun waitForTransitionFinish() {
        repository.transitionStatus.filter { it is TransitionFinished }.first()
    }

    override suspend fun waitForTransitionStart() {
        repository.transitionStatus.filter { it is TransitionStarted }.first()
    }
}
