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

package com.android.systemui.display.domain.interactor

import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.DisplayRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/** Provides information about the status of Rear Display Mode. */
interface RearDisplayStateInteractor {

    /** A flow notifying the subscriber of Rear Display state changes */
    val state: Flow<State>

    sealed class State {
        /** Indicates that the rear display is disabled */
        data object Disabled : State()

        /**
         * Indicates that the device is in Rear Display Mode, and that the inner display is ready to
         * show a system-provided affordance allowing the user to cancel out of the Rear Display
         * Mode.
         */
        data class Enabled(val innerDisplay: Display) : State()
    }
}

@SysUISingleton
class RearDisplayStateInteractorImpl
@Inject
constructor(
    displayRepository: DisplayRepository,
    deviceStateRepository: DeviceStateRepository,
    @Background backgroundCoroutineDispatcher: CoroutineDispatcher,
) : RearDisplayStateInteractor {

    override val state: Flow<RearDisplayStateInteractor.State> =
        deviceStateRepository.state
            .combineTransform(displayRepository.displays) { state, displays ->
                val innerDisplay = displays.find { it.flags and Display.FLAG_REAR != 0 }

                if (state != DeviceStateRepository.DeviceState.REAR_DISPLAY_OUTER_DEFAULT) {
                    emit(RearDisplayStateInteractor.State.Disabled)
                } else if (innerDisplay != null) {
                    emit(RearDisplayStateInteractor.State.Enabled(innerDisplay))
                }
            }
            .distinctUntilChanged()
            .flowOn(backgroundCoroutineDispatcher)
}
