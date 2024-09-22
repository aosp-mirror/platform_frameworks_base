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

package com.android.systemui.media.controls.ui.viewmodel

import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon

/** Models UI state for media player. */
data class MediaPlayerViewModel(
    val contentDescription: (Boolean) -> CharSequence,
    val backgroundCover: android.graphics.drawable.Icon?,
    val appIcon: android.graphics.drawable.Icon?,
    val launcherIcon: Icon,
    val useGrayColorFilter: Boolean,
    val artistName: CharSequence,
    val titleName: CharSequence,
    val isExplicitVisible: Boolean,
    val canShowTime: Boolean,
    val playTurbulenceNoise: Boolean,
    val useSemanticActions: Boolean,
    val actionButtons: List<MediaActionViewModel>,
    val outputSwitcher: MediaOutputSwitcherViewModel,
    val gutsMenu: GutsViewModel,
    val onClicked: (Expandable) -> Unit,
    val onLongClicked: () -> Unit,
    val onSeek: () -> Unit,
    val onBindSeekbar: (SeekBarViewModel) -> Unit,
    val onLocationChanged: (Int) -> Unit,
) {
    fun contentEquals(other: MediaPlayerViewModel?): Boolean {
        return other?.let {
            other.backgroundCover == backgroundCover &&
                appIcon == other.appIcon &&
                useGrayColorFilter == other.useGrayColorFilter &&
                artistName == other.artistName &&
                titleName == other.titleName &&
                isExplicitVisible == other.isExplicitVisible &&
                canShowTime == other.canShowTime &&
                playTurbulenceNoise == other.playTurbulenceNoise &&
                useSemanticActions == other.useSemanticActions &&
                areActionsEqual(other.actionButtons) &&
                outputSwitcher.contentEquals(other.outputSwitcher)
        } ?: false
    }

    private fun areActionsEqual(other: List<MediaActionViewModel>): Boolean {
        actionButtons.forEachIndexed { index, mediaActionViewModel ->
            if (!mediaActionViewModel.contentEquals(other[index])) {
                return false
            }
        }
        return true
    }
}
