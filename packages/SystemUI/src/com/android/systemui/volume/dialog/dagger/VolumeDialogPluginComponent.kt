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

import com.android.systemui.volume.dialog.dagger.module.VolumeDialogPluginModule
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPlugin
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogPluginViewModel
import dagger.BindsInstance
import dagger.Subcomponent
import kotlinx.coroutines.CoroutineScope

/**
 * Volume Dialog plugin dagger component. It's managed by
 * [com.android.systemui.volume.dialog.VolumeDialogPlugin] and lives alongside it.
 */
@VolumeDialogPluginScope
@Subcomponent(modules = [VolumeDialogPluginModule::class])
interface VolumeDialogPluginComponent {

    fun viewModel(): VolumeDialogPluginViewModel

    @Subcomponent.Factory
    interface Factory {

        fun create(
            @BindsInstance @VolumeDialogPlugin scope: CoroutineScope
        ): VolumeDialogPluginComponent
    }
}
