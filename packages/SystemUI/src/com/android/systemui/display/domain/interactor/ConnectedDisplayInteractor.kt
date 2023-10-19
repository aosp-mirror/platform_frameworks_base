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

package com.android.systemui.display.domain.interactor

import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.State
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Provides information about an external connected display. */
interface ConnectedDisplayInteractor {
    /**
     * Provides the current external display state.
     *
     * The state is:
     * - [State.CONNECTED] when there is at least one display with [TYPE_EXTERNAL].
     * - [State.CONNECTED_SECURE] when is at least one display with both [TYPE_EXTERNAL] AND
     *   [Display.FLAG_SECURE] set
     */
    val connectedDisplayState: Flow<State>

    /** Possible connected display state. */
    enum class State {
        DISCONNECTED,
        CONNECTED,
        CONNECTED_SECURE,
    }
}

@SysUISingleton
class ConnectedDisplayInteractorImpl
@Inject
constructor(
    displayRepository: DisplayRepository,
) : ConnectedDisplayInteractor {

    override val connectedDisplayState: Flow<State> =
        displayRepository.displays
            .map { displays ->
                val externalDisplays =
                    displays.filter { display -> display.type == Display.TYPE_EXTERNAL }

                val secureExternalDisplays =
                    externalDisplays.filter { it.flags and Display.FLAG_SECURE != 0 }

                if (externalDisplays.isEmpty()) {
                    State.DISCONNECTED
                } else if (!secureExternalDisplays.isEmpty()) {
                    State.CONNECTED_SECURE
                } else {
                    State.CONNECTED
                }
            }
            .distinctUntilChanged()
}
