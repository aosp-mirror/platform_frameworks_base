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

package android.view

import android.graphics.Region
import com.android.systemui.kosmos.Kosmos
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

val Kosmos.mockWindowManagerService: IWindowManager by
    Kosmos.Fixture {
        mock(IWindowManager::class.java).apply {
            whenever(registerSystemGestureExclusionListener(any(), anyInt())).then { answer ->
                val listener = answer.arguments[0] as ISystemGestureExclusionListener
                val displayId = answer.arguments[1] as Int
                exclusionListeners.getOrPut(displayId) { mutableListOf() }.add(listener)
                listener.onSystemGestureExclusionChanged(
                    displayId,
                    restrictedRegionByDisplayId[displayId],
                    null,
                )
            }

            whenever(unregisterSystemGestureExclusionListener(any(), anyInt())).then { answer ->
                val listener = answer.arguments[0] as ISystemGestureExclusionListener
                val displayId = answer.arguments[1] as Int
                exclusionListeners[displayId]?.remove(listener)
            }
        }
    }

var Kosmos.windowManagerService: IWindowManager by Kosmos.Fixture { mockWindowManagerService }

private var restrictedRegionByDisplayId = mutableMapOf<Int, Region?>()
private var exclusionListeners = mutableMapOf<Int, MutableList<ISystemGestureExclusionListener>>()

fun setSystemGestureExclusionRegion(displayId: Int, restrictedRegion: Region?) {
    restrictedRegionByDisplayId[displayId] = restrictedRegion
    exclusionListeners[displayId]?.forEach { listener ->
        listener.onSystemGestureExclusionChanged(displayId, restrictedRegion, null)
    }
}
