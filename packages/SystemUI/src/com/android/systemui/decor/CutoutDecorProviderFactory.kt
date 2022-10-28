/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.decor

import android.content.res.Resources
import android.util.Log
import android.view.Display
import android.view.DisplayCutout
import android.view.DisplayInfo

class CutoutDecorProviderFactory constructor(
    private val res: Resources,
    private val display: Display?,
) : DecorProviderFactory() {

    val displayInfo = DisplayInfo()

    override val hasProviders: Boolean
        get() {
            display?.getDisplayInfo(displayInfo) ?: run {
                Log.w(TAG, "display is null, can't update displayInfo")
            }
            return DisplayCutout.getFillBuiltInDisplayCutout(res, displayInfo.uniqueId)
        }

    override val providers: List<DecorProvider>
        get() {
            if (!hasProviders) {
                return emptyList()
            }

            return ArrayList<DecorProvider>().also { list ->
                // We need to update displayInfo before using it, but it has already updated during
                // accessing hasProviders field
                displayInfo.displayCutout?.getBoundBaseOnCurrentRotation()?.let { bounds ->
                    for (bound in bounds) {
                        list.add(
                            CutoutDecorProviderImpl(bound.baseOnRotation0(displayInfo.rotation))
                        )
                    }
                }
            }
        }
}

private const val TAG = "CutoutDecorProviderFactory"
