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

package com.android.systemui.volume.dialog.sliders.dagger

import android.content.applicationContext
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.volumeDialogController
import com.android.systemui.shared.notifications.data.repository.notificationSettingsRepository
import com.android.systemui.statusbar.policy.data.repository.deviceProvisioningRepository
import com.android.systemui.statusbar.policy.data.repository.userSetupRepository
import com.android.systemui.statusbar.policy.data.repository.zenModeRepository
import com.android.systemui.statusbar.policy.devicePostureController
import com.android.systemui.volume.data.repository.audioRepository
import com.android.systemui.volume.dialog.data.repository.volumeDialogStateRepository
import com.android.systemui.volume.dialog.data.repository.volumeDialogVisibilityRepository
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import com.android.systemui.volume.dialog.sliders.domain.model.volumeDialogSliderType
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogOverscrollViewBinder
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderViewBinder
import com.android.systemui.volume.dialog.sliders.ui.volumeDialogOverscrollViewBinder
import com.android.systemui.volume.dialog.sliders.ui.volumeDialogSliderViewBinder
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.mediaControllerInteractor

private val Kosmos.mutableSliderComponentKosmoses: MutableMap<VolumeDialogSliderType, Kosmos> by
    Kosmos.Fixture { mutableMapOf() }

val Kosmos.volumeDialogSliderComponentFactory by
    Kosmos.Fixture {
        object : VolumeDialogSliderComponent.Factory {
            override fun create(sliderType: VolumeDialogSliderType): VolumeDialogSliderComponent =
                volumeDialogSliderComponent(sliderType)
        }
    }

fun Kosmos.volumeDialogSliderComponent(type: VolumeDialogSliderType): VolumeDialogSliderComponent {
    return object : VolumeDialogSliderComponent {

        private val localKosmos
            get() =
                mutableSliderComponentKosmoses.getOrPut(type) {
                    Kosmos().also {
                        it.setupVolumeDialogSliderComponent(this@volumeDialogSliderComponent, type)
                    }
                }

        override fun sliderViewBinder(): VolumeDialogSliderViewBinder =
            localKosmos.volumeDialogSliderViewBinder

        override fun overscrollViewBinder(): VolumeDialogOverscrollViewBinder =
            localKosmos.volumeDialogOverscrollViewBinder
    }
}

private fun Kosmos.setupVolumeDialogSliderComponent(
    parentKosmos: Kosmos,
    type: VolumeDialogSliderType,
) {
    volumeDialogSliderType = type
    applicationContext = parentKosmos.applicationContext
    testScope = parentKosmos.testScope
    testDispatcher = parentKosmos.testDispatcher

    audioRepository = parentKosmos.audioRepository
    devicePostureController = parentKosmos.devicePostureController

    volumeDialogController = parentKosmos.volumeDialogController
    volumeDialogStateRepository = parentKosmos.volumeDialogStateRepository

    mediaControllerInteractor = parentKosmos.mediaControllerInteractor
    mediaControllerRepository = parentKosmos.mediaControllerRepository

    zenModeRepository = parentKosmos.zenModeRepository
    volumeDialogVisibilityRepository = parentKosmos.volumeDialogVisibilityRepository
    notificationSettingsRepository = parentKosmos.notificationSettingsRepository
    deviceProvisioningRepository = parentKosmos.deviceProvisioningRepository
    userSetupRepository = parentKosmos.userSetupRepository
}
