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

package com.android.systemui.volume.domain.startable

import com.android.internal.logging.UiEventLogger
import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.domain.VolumePanelStartable
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Logger for audio mode */
@VolumePanelScope
class AudioModeLoggerStartable
@Inject
constructor(
    @VolumePanelScope private val scope: CoroutineScope,
    private val uiEventLogger: UiEventLogger,
    private val audioModeInteractor: AudioModeInteractor,
) : VolumePanelStartable {

    override fun start() {
        scope.launch {
            audioModeInteractor.isOngoingCall.distinctUntilChanged().collect { ongoingCall ->
                uiEventLogger.log(
                    if (ongoingCall) VolumePanelUiEvent.VOLUME_PANEL_AUDIO_MODE_CHANGE_TO_CALLING
                    else VolumePanelUiEvent.VOLUME_PANEL_AUDIO_MODE_CHANGE_TO_NORMAL
                )
            }
        }
    }
}
