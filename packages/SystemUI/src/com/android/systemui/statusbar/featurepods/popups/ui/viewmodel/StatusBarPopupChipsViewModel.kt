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

package com.android.systemui.statusbar.featurepods.popups.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.featurepods.media.ui.viewmodel.MediaControlChipViewModel
import com.android.systemui.statusbar.featurepods.popups.StatusBarPopupChips
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * View model deciding which system process chips to show in the status bar. Emits a list of
 * PopupChipModels.
 */
@SysUISingleton
class StatusBarPopupChipsViewModel
@Inject
constructor(
    @Background scope: CoroutineScope,
    mediaControlChipViewModel: MediaControlChipViewModel,
) {
    private data class PopupChipBundle(
        val media: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.MediaControl)
    )

    private val incomingPopupChipBundle: StateFlow<PopupChipBundle?> =
        mediaControlChipViewModel.chip
            .map { chip -> PopupChipBundle(media = chip) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), PopupChipBundle())

    val shownPopupChips: StateFlow<List<PopupChipModel.Shown>> =
        if (StatusBarPopupChips.isEnabled) {
            incomingPopupChipBundle
                .map { bundle ->
                    listOfNotNull(bundle?.media).filterIsInstance<PopupChipModel.Shown>()
                }
                .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())
        } else {
            MutableStateFlow(emptyList<PopupChipModel.Shown>()).asStateFlow()
        }
}
