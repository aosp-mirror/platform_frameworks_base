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

package com.android.systemui.volume.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.volume.data.repository.VolumeDialogRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** An interactor that propagates the UI states of the Volume Dialog. */
@SysUISingleton
class VolumeDialogInteractor
@Inject
constructor(
    private val repository: VolumeDialogRepository,
) {
    /** Whether the Volume Dialog is visible. */
    val isDialogVisible: StateFlow<Boolean> = repository.isDialogVisible

    /** Notifies that the Volume Dialog is shown. */
    fun onDialogShown() {
        repository.setDialogVisibility(true)
    }

    /** Notifies that the Volume Dialog has been dismissed. */
    fun onDialogDismissed() {
        repository.setDialogVisibility(false)
    }
}
