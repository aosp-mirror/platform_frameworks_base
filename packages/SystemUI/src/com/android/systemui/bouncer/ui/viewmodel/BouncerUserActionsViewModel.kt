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

package com.android.systemui.bouncer.ui.viewmodel

import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.map

/**
 * Models UI state for user actions that can lead to navigation to other scenes when showing the
 * bouncer scene.
 */
class BouncerUserActionsViewModel
@AssistedInject
constructor(
    private val bouncerInteractor: BouncerInteractor,
) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        bouncerInteractor.dismissDestination
            .map { prevScene ->
                mapOf(
                    Back to UserActionResult(prevScene),
                    Swipe(SwipeDirection.Down) to UserActionResult(prevScene),
                )
            }
            .collect { actions -> setActions(actions) }
    }

    @AssistedFactory
    interface Factory {
        fun create(): BouncerUserActionsViewModel
    }
}
