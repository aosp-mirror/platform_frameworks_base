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

package com.android.systemui.volume.panel.component.spatial.ui.viewmodel

import android.content.Context
import com.android.internal.logging.UiEventLogger
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.button.ui.viewmodel.ButtonViewModel
import com.android.systemui.volume.panel.component.spatial.domain.SpatialAudioAvailabilityCriteria
import com.android.systemui.volume.panel.component.spatial.domain.interactor.SpatialAudioComponentInteractor
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioAvailabilityModel
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioEnabledModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@VolumePanelScope
class SpatialAudioViewModel
@Inject
constructor(
    @Application private val context: Context,
    @VolumePanelScope private val scope: CoroutineScope,
    availabilityCriteria: SpatialAudioAvailabilityCriteria,
    private val interactor: SpatialAudioComponentInteractor,
    private val uiEventLogger: UiEventLogger,
) {

    private val spatialSpeakerIcon =
        Icon.Resource(R.drawable.ic_spatial_speaker, contentDescription = null)

    val spatialAudioButton: StateFlow<ButtonViewModel?> =
        combine(interactor.isEnabled, interactor.isAvailable) { isEnabled, isAvailable ->
                isEnabled
                    .toViewModel(
                        isChecked = isEnabled is SpatialAudioEnabledModel.SpatialAudioEnabled,
                        isHeadTrackingAvailable =
                            isAvailable is SpatialAudioAvailabilityModel.HeadTracking,
                    )
                    .copy(label = context.getString(R.string.volume_panel_spatial_audio_title))
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val shouldUsePopup: StateFlow<Boolean> =
        interactor.isAvailable
            .map {
                // head tracking availability means there are three possible states for the spatial
                // audio: disabled, enabled regular, enabled with head tracking.
                // Show popup in this case instead of a togglealbe button.
                it is SpatialAudioAvailabilityModel.HeadTracking
            }
            .stateIn(scope, SharingStarted.Eagerly, false)

    val isAvailable: StateFlow<Boolean> =
        availabilityCriteria.isAvailable().stateIn(scope, SharingStarted.Eagerly, true)

    val spatialAudioButtons: StateFlow<List<SpatialAudioButtonViewModel>> =
        combine(interactor.isEnabled, interactor.isAvailable) { currentIsEnabled, isAvailable ->
                SpatialAudioEnabledModel.values
                    .filter {
                        if (it is SpatialAudioEnabledModel.HeadTrackingEnabled) {
                            // Spatial audio control can be visible when there is spatial audio
                            // setting available but not the head tracking.
                            isAvailable is SpatialAudioAvailabilityModel.HeadTracking
                        } else {
                            true
                        }
                    }
                    .map { isEnabled ->
                        val buttonViewModel: ButtonViewModel =
                            isEnabled.toViewModel(
                                isChecked = isEnabled == currentIsEnabled,
                                isHeadTrackingAvailable =
                                    isAvailable is SpatialAudioAvailabilityModel.HeadTracking,
                            )
                        SpatialAudioButtonViewModel(button = buttonViewModel, model = isEnabled)
                    }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun setEnabled(model: SpatialAudioEnabledModel) {
        uiEventLogger.logWithPosition(
            VolumePanelUiEvent.VOLUME_PANEL_SPATIAL_AUDIO_TOGGLE_CLICKED,
            0,
            null,
            when (model) {
                SpatialAudioEnabledModel.Disabled -> 0
                SpatialAudioEnabledModel.SpatialAudioEnabled -> 1
                SpatialAudioEnabledModel.HeadTrackingEnabled -> 2
                else -> {
                    -1
                }
            }
        )
        scope.launch { interactor.setEnabled(model) }
    }

    private fun SpatialAudioEnabledModel.toViewModel(
        isChecked: Boolean,
        isHeadTrackingAvailable: Boolean,
    ): ButtonViewModel {
        // This method deliberately uses the same icon for the case when head tracking is disabled
        // to show a toggle button with a non-changing icon
        if (this is SpatialAudioEnabledModel.HeadTrackingEnabled) {
            return ButtonViewModel(
                isActive = isChecked,
                icon =
                    if (isHeadTrackingAvailable) {
                        Icon.Resource(R.drawable.ic_head_tracking, contentDescription = null)
                    } else {
                        spatialSpeakerIcon
                    },
                label = context.getString(R.string.volume_panel_spatial_audio_tracking)
            )
        }

        if (this is SpatialAudioEnabledModel.SpatialAudioEnabled) {
            return ButtonViewModel(
                isActive = isChecked,
                icon =
                    if (isHeadTrackingAvailable) {
                        Icon.Resource(R.drawable.ic_spatial_audio, contentDescription = null)
                    } else {
                        spatialSpeakerIcon
                    },
                label = context.getString(R.string.volume_panel_spatial_audio_fixed)
            )
        }

        if (this is SpatialAudioEnabledModel.Disabled) {
            return ButtonViewModel(
                isActive = isChecked,
                icon =
                    if (isHeadTrackingAvailable) {
                        Icon.Resource(R.drawable.ic_spatial_audio_off, contentDescription = null)
                    } else {
                        spatialSpeakerIcon
                    },
                label = context.getString(R.string.volume_panel_spatial_audio_off)
            )
        }

        error("Unsupported model: $this")
    }
}
