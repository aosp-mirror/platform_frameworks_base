/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.volume.panel.dagger

import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Provides Volume Panel coroutine tools. */
@Module
interface CoroutineModule {

    companion object {

        /**
         * Provides a coroutine scope to use inside [VolumePanelScope].
         * [com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel] manages the
         * lifecycle of this scope. It's cancelled when the View Model is destroyed. This helps to
         * free occupied resources when volume panel is not shown.
         */
        @VolumePanelScope
        @Provides
        fun provideCoroutineScope(@Application applicationScope: CoroutineScope): CoroutineScope =
            CoroutineScope(applicationScope.coroutineContext + SupervisorJob())
    }
}
