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

package com.android.systemui.smartspace.ui.viewmodel

import com.android.systemui.power.domain.interactor.PowerInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

class SmartspaceViewModel
@AssistedInject
constructor(
    powerInteractor: PowerInteractor,
    @Assisted val surfaceName: String,
) {

    /** Screen on/off state */
    val isAwake: Flow<Boolean> =
        powerInteractor.isAwake.filter { surfaceName != SURFACE_WEATHER_VIEW }

    @AssistedFactory
    interface Factory {
        fun create(surfaceName: String): SmartspaceViewModel
    }

    companion object {
        const val SURFACE_DATE_VIEW = "date_view"
        const val SURFACE_WEATHER_VIEW = "weather_view"
        const val SURFACE_GENERAL_VIEW = "general_view"
    }
}
