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

package com.android.systemui.navigation.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class NavigationRepository
@Inject
constructor(
    private val controller: NavigationModeController,
) {

    /** Whether the current navigation bar mode is edge-to-edge. */
    val isGesturalMode: Flow<Boolean> = conflatedCallbackFlow {
        val listener =
            NavigationModeController.ModeChangedListener { mode ->
                trySend(QuickStepContract.isGesturalMode(mode))
            }

        val currentMode = controller.addListener(listener)
        trySend(QuickStepContract.isGesturalMode(currentMode))

        awaitClose { controller.removeListener(listener) }
    }
}
