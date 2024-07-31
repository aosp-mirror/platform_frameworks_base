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

package com.android.systemui.statusbar.chips.ui.viewmodel

import android.annotation.SuppressLint
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * A class that can help [OngoingActivityChipViewModel] instances with various transition states.
 *
 * For now, this class's only functionality is immediately hiding the chip if the user has tapped an
 * activity chip and then clicked "Stop" on the resulting dialog. There's a bit of a delay between
 * when the user clicks "Stop" and when the system services notify SysUI that the activity has
 * indeed stopped. We don't want the chip to briefly show for a few frames during that delay, so
 * this class helps us immediately hide the chip as soon as the user clicks "Stop" in the dialog.
 * See b/353249803#comment4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChipTransitionHelper(@Application private val scope: CoroutineScope) {
    /** A flow that emits each time the user has clicked "Stop" on the dialog. */
    @SuppressLint("SharedFlowCreation")
    private val activityStoppedFromDialogEvent = MutableSharedFlow<Unit>()

    /** True if the user recently stopped the activity from the dialog. */
    private val wasActivityRecentlyStoppedFromDialog: Flow<Boolean> =
        activityStoppedFromDialogEvent
            .transformLatest {
                // Give system services 500ms to stop the activity and notify SysUI. Once more than
                // 500ms has elapsed, we should go back to using the current system service
                // information as the source of truth.
                emit(true)
                delay(500)
                emit(false)
            }
            // Use stateIn so that the flow created in [createChipFlow] is guaranteed to
            // emit. (`combine`s require that all input flows have emitted.)
            .stateIn(scope, SharingStarted.Lazily, false)

    /**
     * Notifies this class that the user just clicked "Stop" on the stop dialog that's shown when
     * the chip is tapped.
     *
     * Call this method in order to immediately hide the chip.
     */
    fun onActivityStoppedFromDialog() {
        // Because this event causes UI changes, make sure it's launched on the main thread scope.
        scope.launch { activityStoppedFromDialogEvent.emit(Unit) }
    }

    /**
     * Creates a flow that will forcibly hide the chip if the user recently stopped the activity
     * (see [onActivityStoppedFromDialog]). In general, this flow just uses value in [chip].
     */
    fun createChipFlow(chip: Flow<OngoingActivityChipModel>): StateFlow<OngoingActivityChipModel> {
        return combine(
                chip,
                wasActivityRecentlyStoppedFromDialog,
            ) { chipModel, activityRecentlyStopped ->
                if (activityRecentlyStopped) {
                    // There's a bit of a delay between when the user stops an activity via
                    // SysUI and when the system services notify SysUI that the activity has
                    // indeed stopped. Prevent the chip from showing during this delay by
                    // immediately hiding it without any animation.
                    OngoingActivityChipModel.Hidden(shouldAnimate = false)
                } else {
                    chipModel
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden())
    }
}
