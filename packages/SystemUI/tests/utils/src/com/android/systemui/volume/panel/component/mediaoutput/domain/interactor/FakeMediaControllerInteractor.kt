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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import android.media.session.MediaController
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaControllerChangeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeMediaControllerInteractor : MediaControllerInteractor {

    private val stateChanges = MutableSharedFlow<MediaControllerChangeModel>(replay = 1)

    override fun stateChanges(mediaController: MediaController): Flow<MediaControllerChangeModel> =
        stateChanges

    fun updateState(change: MediaControllerChangeModel) {
        stateChanges.tryEmit(change)
    }
}
