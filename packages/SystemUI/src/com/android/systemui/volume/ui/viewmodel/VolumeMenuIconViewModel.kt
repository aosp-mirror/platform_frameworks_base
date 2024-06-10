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

package com.android.systemui.volume.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.util.drawable.LoopedAnimatable2DrawableWrapper
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaDeviceSessionInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.shared.model.filterData
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest

/** View Model that provides an icon for the menu button of the volume dialog. */
interface VolumeMenuIconViewModel {

    val icon: Flow<Icon>
}

@OptIn(ExperimentalCoroutinesApi::class)
@SuppressLint("UseCompatLoadingForDrawables")
class AnimatedVolumeMenuIconViewModel
@Inject
constructor(
    @Application private val context: Context,
    private val mediaOutputInteractor: MediaOutputInteractor,
    private val mediaDeviceSessionInteractor: MediaDeviceSessionInteractor,
) : VolumeMenuIconViewModel {

    override val icon: Flow<Icon>
        get() =
            mediaOutputInteractor.defaultActiveMediaSession
                .filterData()
                .flatMapLatest { session ->
                    if (session == null) {
                        flowOf(null)
                    } else {
                        mediaDeviceSessionInteractor.playbackState(session)
                    }
                }
                .distinctUntilChangedBy { it?.isActive }
                .mapLatest { playbackState ->
                    if (playbackState?.isActive == true) {
                        Icon.Loaded(
                            LoopedAnimatable2DrawableWrapper.fromDrawable(
                                context.getDrawable(R.drawable.audio_bars_playing)!!
                            ),
                            null,
                        )
                    } else {
                        Icon.Resource(R.drawable.horizontal_ellipsis, null)
                    }
                }
}

class StaticVolumeMenuIconViewModel @Inject constructor() : VolumeMenuIconViewModel {

    override val icon: Flow<Icon> = flowOf(Icon.Resource(R.drawable.horizontal_ellipsis, null))
}
