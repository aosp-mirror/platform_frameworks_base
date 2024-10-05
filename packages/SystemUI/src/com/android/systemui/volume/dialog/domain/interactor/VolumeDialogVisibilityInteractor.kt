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

package com.android.systemui.volume.dialog.domain.interactor

import android.annotation.SuppressLint
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPlugin
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.domain.model.VolumeDialogEventModel
import com.android.systemui.volume.dialog.domain.model.VolumeDialogVisibilityModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

private val maxDialogShowTime: Duration = 3.seconds

/**
 * Handles Volume Dialog visibility state. It might change from several sources:
 * - [com.android.systemui.plugins.VolumeDialogController] requests visibility change;
 * - it might be dismissed by the inactivity timeout;
 * - it can be dismissed by the user;
 */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogPluginScope
class VolumeDialogVisibilityInteractor
@Inject
constructor(
    @VolumeDialogPlugin coroutineScope: CoroutineScope,
    callbacksInteractor: VolumeDialogCallbacksInteractor,
) {

    @SuppressLint("SharedFlowCreation")
    private val mutableDismissDialogEvents = MutableSharedFlow<Unit>()
    private val mutableDialogVisibility =
        MutableStateFlow<VolumeDialogVisibilityModel>(VolumeDialogVisibilityModel.Invisible)

    val dialogVisibility: Flow<VolumeDialogVisibilityModel> = mutableDialogVisibility.asStateFlow()

    init {
        merge(
                mutableDismissDialogEvents.mapLatest {
                    delay(maxDialogShowTime)
                    VolumeDialogEventModel.DismissRequested(Events.DISMISS_REASON_TIMEOUT)
                },
                callbacksInteractor.event,
            )
            .onEach { event ->
                VolumeDialogVisibilityModel.fromEvent(event)?.let { model ->
                    mutableDialogVisibility.value = model
                    if (model is VolumeDialogVisibilityModel.Visible) {
                        resetDismissTimeout()
                    }
                }
            }
            .launchIn(coroutineScope)
    }

    /**
     * Dismisses the dialog with a given [reason]. The new state will be emitted in the
     * [dialogVisibility].
     */
    fun dismissDialog(reason: Int) {
        mutableDialogVisibility.update {
            if (it is VolumeDialogVisibilityModel.Dismissed) {
                it
            } else {
                VolumeDialogVisibilityModel.Dismissed(reason)
            }
        }
    }

    /** Resets current dialog timeout. */
    suspend fun resetDismissTimeout() {
        mutableDismissDialogEvents.emit(Unit)
    }
}
