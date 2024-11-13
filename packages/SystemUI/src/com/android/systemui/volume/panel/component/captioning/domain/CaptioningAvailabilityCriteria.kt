/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.panel.component.captioning.domain

import com.android.internal.logging.UiEventLogger
import com.android.systemui.accessibility.domain.interactor.CaptioningInteractor
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.domain.ComponentAvailabilityCriteria
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@VolumePanelScope
class CaptioningAvailabilityCriteria
@Inject
constructor(
    captioningInteractor: CaptioningInteractor,
    @VolumePanelScope private val scope: CoroutineScope,
    private val uiEventLogger: UiEventLogger,
) : ComponentAvailabilityCriteria {

    private val availability =
        captioningInteractor.isSystemAudioCaptioningUiEnabled
            .onEach { visible ->
                uiEventLogger.log(
                    if (visible) VolumePanelUiEvent.VOLUME_PANEL_LIVE_CAPTION_TOGGLE_SHOWN
                    else VolumePanelUiEvent.VOLUME_PANEL_LIVE_CAPTION_TOGGLE_GONE
                )
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override fun isAvailable(): Flow<Boolean> = availability
}
