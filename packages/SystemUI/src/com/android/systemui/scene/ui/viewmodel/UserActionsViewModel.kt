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

package com.android.systemui.scene.ui.viewmodel

import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.lifecycle.ExclusiveActivatable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base class for view-models that need to keep a map of user actions up-to-date.
 *
 * Subclasses need only to override [hydrateActions], suspending forever if they need; they don't
 * need to worry about resetting the value of [actions] when the view-model is deactivated/canceled,
 * this base class takes care of it.
 */
abstract class UserActionsViewModel : ExclusiveActivatable() {

    private val _actions = MutableStateFlow<Map<UserAction, UserActionResult>>(emptyMap())
    /**
     * [UserActionResult] by [UserAction] to be collected by the scene container to enable the right
     * user input/gestures.
     */
    val actions: StateFlow<Map<UserAction, UserActionResult>> = _actions.asStateFlow()

    final override suspend fun onActivated(): Nothing {
        try {
            hydrateActions { state -> _actions.value = state }
            awaitCancellation()
        } finally {
            _actions.value = emptyMap()
        }
    }

    /**
     * Keeps the user actions up-to-date (AKA "hydrated").
     *
     * Subclasses should implement this `suspend fun` by running coroutine work and calling
     * [setActions] each time the actions should be published/updated. The work can safely suspend
     * forever; the base class will take care of canceling it as needed. There's no need to handle
     * cancellation in this method.
     *
     * The base class will also take care of resetting the [actions] flow back to the default value
     * when this happens.
     */
    protected abstract suspend fun hydrateActions(
        setActions: (Map<UserAction, UserActionResult>) -> Unit,
    )
}
