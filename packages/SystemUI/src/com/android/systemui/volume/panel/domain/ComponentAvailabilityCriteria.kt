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

package com.android.systemui.volume.panel.domain

import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface ComponentAvailabilityCriteria {

    /**
     * Checks if the controller is currently available. Can be used to filter out unwanted
     * components. For example, hide components for the hardware that is temporarily unavailable.
     */
    fun isAvailable(): Flow<Boolean>
}

@VolumePanelScope
class AlwaysAvailableCriteria @Inject constructor() : ComponentAvailabilityCriteria {

    override fun isAvailable(): Flow<Boolean> = flowOf(true)
}
