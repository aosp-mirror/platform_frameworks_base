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

package com.android.systemui.scene.data.repository

import android.graphics.Region
import android.view.ISystemGestureExclusionListener
import android.view.IWindowManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class SystemGestureExclusionRepository
@Inject
constructor(private val windowManager: IWindowManager) {

    /**
     * Returns [Flow] of the [Region] in which system gestures should be excluded on the display
     * identified with [displayId].
     */
    fun exclusionRegion(displayId: Int): Flow<Region?> {
        return conflatedCallbackFlow {
            val listener =
                object : ISystemGestureExclusionListener.Stub() {
                    override fun onSystemGestureExclusionChanged(
                        displayId: Int,
                        restrictedRegion: Region?,
                        unrestrictedRegion: Region?,
                    ) {
                        trySend(restrictedRegion)
                    }
                }
            windowManager.registerSystemGestureExclusionListener(listener, displayId)

            awaitClose {
                windowManager.unregisterSystemGestureExclusionListener(listener, displayId)
            }
        }
    }
}
