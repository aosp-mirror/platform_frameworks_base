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

package com.android.systemui.statusbar.featurepods.media.ui.viewmodel

import android.content.Context
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.featurepods.media.domain.interactor.MediaControlChipInteractor
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.StatusBarPopupChipViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [StatusBarPopupChipViewModel] for a media control chip in the status bar. This view model is
 * responsible for converting the [MediaControlChipModel] to a [PopupChipModel] that can be used to
 * display a media control chip.
 */
@SysUISingleton
class MediaControlChipViewModel
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    @Application private val applicationContext: Context,
    mediaControlChipInteractor: MediaControlChipInteractor,
) : StatusBarPopupChipViewModel {

    /**
     * A [StateFlow] of the current [PopupChipModel]. This flow emits a new [PopupChipModel]
     * whenever the underlying [MediaControlChipModel] changes.
     */
    override val chip: StateFlow<PopupChipModel> =
        mediaControlChipInteractor.mediaControlModel
            .map { mediaControlModel -> toPopupChipModel(mediaControlModel, applicationContext) }
            .stateIn(
                backgroundScope,
                SharingStarted.WhileSubscribed(),
                PopupChipModel.Hidden(PopupChipId.MediaControl),
            )
}

private fun toPopupChipModel(model: MediaControlChipModel?, context: Context): PopupChipModel {
    if (model == null || model.songName.isNullOrEmpty()) {
        return PopupChipModel.Hidden(PopupChipId.MediaControl)
    }

    val contentDescription = model.appName?.let { ContentDescription.Loaded(description = it) }
    return PopupChipModel.Shown(
        chipId = PopupChipId.MediaControl,
        icon =
            model.appIcon?.loadDrawable(context)?.let {
                Icon.Loaded(drawable = it, contentDescription = contentDescription)
            }
                ?: Icon.Resource(
                    res = com.android.internal.R.drawable.ic_audio_media,
                    contentDescription = contentDescription,
                ),
        hoverIcon =
            Icon.Resource(
                res = com.android.internal.R.drawable.ic_media_pause,
                contentDescription = null,
            ),
        chipText = model.songName.toString(),
        isToggled = false,
        // TODO(b/385202114): Show a popup containing the media carousal when the chip is toggled.
        onToggle = {},
        // TODO(b/385202193): Add support for clicking on the icon on a media chip.
        onIconPressed = {},
    )
}
