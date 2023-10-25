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

package com.android.systemui.qs.tiles.viewmodel

import android.os.UserHandle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents tiles behaviour logic. This ViewModel is a connection between tile view and data
 * layers. All direct inheritors must be added to the [QSTileViewModelInterfaceComplianceTest] class
 * to pass compliance tests.
 *
 * All methods of this view model should be considered running on the main thread. This means no
 * synchronous long running operations are permitted in any method.
 */
interface QSTileViewModel {

    /** State of the tile to be shown by the view. */
    val state: SharedFlow<QSTileState>

    val config: QSTileConfig

    /** Specifies whether this device currently supports this tile. */
    val isAvailable: StateFlow<Boolean>

    /**
     * Notifies about the user change. Implementations should avoid using 3rd party userId sources
     * and use this value instead. This is to maintain consistent and concurrency-free behaviour
     * across different parts of QS.
     */
    fun onUserChanged(user: UserHandle)

    /** Triggers the emission of the new [QSTileState] in a [state]. */
    fun forceUpdate()

    /** Notifies underlying logic about user input. */
    fun onActionPerformed(userAction: QSTileUserAction)

    /**
     * Frees the resources held by this view model. Call it when you no longer need the instance,
     * because there is no guarantee it will work as expected beyond this point.
     */
    fun destroy()
}

/**
 * Returns the immediate state of the tile or null if the state haven't been collected yet. Favor
 * reactive consumption over the [currentState], because there is no guarantee that current value
 * would be available at any time.
 */
val QSTileViewModel.currentState: QSTileState?
    get() = state.replayCache.lastOrNull()
