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

package com.android.systemui.volume.dialog.settings.ui.viewmodel

import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.settings.domain.VolumeDialogSettingsButtonInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class VolumeDialogSettingsButtonViewModel
@AssistedInject
constructor(private val interactor: VolumeDialogSettingsButtonInteractor) {

    val isVisible = interactor.isVisible

    fun onButtonClicked() {
        interactor.onButtonClicked()
    }

    @VolumeDialogScope
    @AssistedFactory
    interface Factory {

        fun create(): VolumeDialogSettingsButtonViewModel
    }
}
