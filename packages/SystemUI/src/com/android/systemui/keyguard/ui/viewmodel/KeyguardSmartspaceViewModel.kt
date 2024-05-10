/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject

@SysUISingleton
class KeyguardSmartspaceViewModel
@Inject
constructor(val context: Context, smartspaceController: LockscreenSmartspaceController) {
    val isSmartspaceEnabled: Boolean = smartspaceController.isEnabled()
    val isWeatherEnabled: Boolean = smartspaceController.isWeatherEnabled()
    val isDateWeatherDecoupled: Boolean = smartspaceController.isDateWeatherDecoupled()
    val smartspaceViewId: Int
        get() = getId("bc_smartspace_view")

    val dateId: Int
        get() = getId("date_smartspace_view")

    val weatherId: Int
        get() = getId("weather_smartspace_view")

    private fun getId(name: String): Int {
        return context.resources.getIdentifier(name, "id", context.packageName).also {
            if (it == 0) {
                Log.d(TAG, "Cannot resolve id $name")
            }
        }
    }
    fun getDimen(name: String): Int {
        val res = context.packageManager.getResourcesForApplication(context.packageName)
        val id = res.getIdentifier(name, "dimen", context.packageName)
        return res.getDimensionPixelSize(id)
    }

    companion object {
        private val TAG = KeyguardSmartspaceViewModel::class.java.simpleName
    }
}
