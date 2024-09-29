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

package com.android.systemui.scene.domain.interactor

import android.graphics.Region
import com.android.systemui.scene.data.repository.SystemGestureExclusionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class SystemGestureExclusionInteractor
@Inject
constructor(private val repository: SystemGestureExclusionRepository) {

    /**
     * Returns [Flow] of the [Region] in which system gestures should be excluded on the display
     * identified with [displayId].
     */
    fun exclusionRegion(displayId: Int): Flow<Region?> {
        return repository.exclusionRegion(displayId)
    }
}
