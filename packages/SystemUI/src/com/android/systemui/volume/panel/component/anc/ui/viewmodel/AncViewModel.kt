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

package com.android.systemui.volume.panel.component.anc.ui.viewmodel

import android.content.Context
import androidx.slice.Slice
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.anc.domain.interactor.AncSliceInteractor
import com.android.systemui.volume.panel.component.button.ui.viewmodel.ButtonViewModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Volume Panel ANC component view model. */
@VolumePanelScope
class AncViewModel
@Inject
constructor(
    @Application private val context: Context,
    @VolumePanelScope private val coroutineScope: CoroutineScope,
    private val interactor: AncSliceInteractor,
) {

    /** ANC [Slice]. Null when there is no slice available for ANC. */
    val slice: StateFlow<Slice?> =
        interactor.ancSlice.stateIn(coroutineScope, SharingStarted.Eagerly, null)

    /**
     * ButtonViewModel to be shown in the VolumePanel. Null when there is no ANC Slice available.
     */
    val button: StateFlow<ButtonViewModel?> =
        interactor.ancSlice
            .map { slice ->
                slice?.let {
                    ButtonViewModel(
                        Icon.Resource(R.drawable.ic_noise_aware, null),
                        context.getString(R.string.volume_panel_noise_control_title)
                    )
                }
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)

    /** Call this to update [slice] width in a reaction to container size change. */
    fun changeSliceWidth(width: Int) {
        interactor.changeWidth(width)
    }
}
