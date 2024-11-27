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

package com.android.systemui.volume.dialog.dagger

import com.android.systemui.volume.dialog.dagger.module.VolumeDialogModule
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderComponent
import com.android.systemui.volume.dialog.ui.binder.VolumeDialogViewBinder
import dagger.BindsInstance
import dagger.Subcomponent
import kotlinx.coroutines.CoroutineScope

/**
 * Core Volume Dialog dagger component. It's managed by
 * [com.android.systemui.volume.dialog.VolumeDialogPlugin] and lives alongside it.
 */
@VolumeDialogScope
@Subcomponent(modules = [VolumeDialogModule::class])
interface VolumeDialogComponent {

    fun volumeDialogViewBinder(): VolumeDialogViewBinder

    fun sliderComponentFactory(): VolumeDialogSliderComponent.Factory

    @Subcomponent.Factory
    interface Factory {

        fun create(
            /**
             * Provides a coroutine scope to use inside [VolumeDialogScope].
             * [com.android.systemui.volume.dialog.VolumeDialogPlugin] manages the lifecycle of this
             * scope. It's cancelled when the dialog is disposed. This helps to free occupied
             * resources when volume dialog is not shown.
             */
            @BindsInstance @VolumeDialog scope: CoroutineScope
        ): VolumeDialogComponent
    }
}
