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

package com.android.systemui.volume.panel.ui

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

/** UI events for Volume Panel. */
enum class VolumePanelUiEvent(val metricId: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "The volume panel is shown") VOLUME_PANEL_SHOWN(1634),
    @UiEvent(doc = "The volume panel is gone") VOLUME_PANEL_GONE(1635),
    @UiEvent(doc = "Media output is clicked") VOLUME_PANEL_MEDIA_OUTPUT_CLICKED(1636),
    @UiEvent(doc = "Audio mode changed to normal") VOLUME_PANEL_AUDIO_MODE_CHANGE_TO_NORMAL(1680),
    @UiEvent(doc = "Audio mode changed to calling") VOLUME_PANEL_AUDIO_MODE_CHANGE_TO_CALLING(1681),
    @UiEvent(doc = "Sound settings is clicked") VOLUME_PANEL_SOUND_SETTINGS_CLICKED(1638),
    @UiEvent(doc = "The music volume slider is touched") VOLUME_PANEL_MUSIC_SLIDER_TOUCHED(1639),
    @UiEvent(doc = "The voice call volume slider is touched")
    VOLUME_PANEL_VOICE_CALL_SLIDER_TOUCHED(1640),
    @UiEvent(doc = "The ring volume slider is touched") VOLUME_PANEL_RING_SLIDER_TOUCHED(1641),
    @UiEvent(doc = "The notification volume slider is touched")
    VOLUME_PANEL_NOTIFICATION_SLIDER_TOUCHED(1642),
    @UiEvent(doc = "The alarm volume slider is touched") VOLUME_PANEL_ALARM_SLIDER_TOUCHED(1643),
    @UiEvent(doc = "Live caption toggle is shown") VOLUME_PANEL_LIVE_CAPTION_TOGGLE_SHOWN(1644),
    @UiEvent(doc = "Live caption toggle is gone") VOLUME_PANEL_LIVE_CAPTION_TOGGLE_GONE(1645),
    @UiEvent(doc = "Live caption toggle is clicked") VOLUME_PANEL_LIVE_CAPTION_TOGGLE_CLICKED(1646),
    @UiEvent(doc = "Spatial audio button is shown") VOLUME_PANEL_SPATIAL_AUDIO_BUTTON_SHOWN(1647),
    @UiEvent(doc = "Spatial audio button is gone") VOLUME_PANEL_SPATIAL_AUDIO_BUTTON_GONE(1648),
    @UiEvent(doc = "Spatial audio popup is shown") VOLUME_PANEL_SPATIAL_AUDIO_POP_UP_SHOWN(1649),
    @UiEvent(doc = "Spatial audio toggle is clicked")
    VOLUME_PANEL_SPATIAL_AUDIO_TOGGLE_CLICKED(1650),
    @UiEvent(doc = "ANC button is shown") VOLUME_PANEL_ANC_BUTTON_SHOWN(1651),
    @UiEvent(doc = "ANC button is gone") VOLUME_PANEL_ANC_BUTTON_GONE(1652),
    @UiEvent(doc = "ANC popup is shown") VOLUME_PANEL_ANC_POPUP_SHOWN(1653),
    @UiEvent(doc = "ANC toggle is clicked") VOLUME_PANEL_ANC_TOGGLE_CLICKED(1654);

    override fun getId() = metricId

    companion object {
        const val LIVE_CAPTION_TOGGLE_DISABLED = 0
        const val LIVE_CAPTION_TOGGLE_ENABLED = 1
    }
}
