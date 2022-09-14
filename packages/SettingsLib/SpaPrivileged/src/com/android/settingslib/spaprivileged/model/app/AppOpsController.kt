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

package com.android.settingslib.spaprivileged.model.app

import android.app.AppOpsManager
import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_ERRORED
import android.app.AppOpsManager.Mode
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations

class AppOpsController(
    context: Context,
    private val app: ApplicationInfo,
    private val op: Int,
) {
    private val appOpsManager = checkNotNull(context.getSystemService(AppOpsManager::class.java))

    val mode: LiveData<Int>
        get() = _mode
    val isAllowed: LiveData<Boolean>
        get() = Transformations.map(_mode) { it == MODE_ALLOWED }

    fun setAllowed(allowed: Boolean) {
        val mode = if (allowed) MODE_ALLOWED else MODE_ERRORED
        appOpsManager.setMode(op, app.uid, app.packageName, mode)
        _mode.postValue(mode)
    }

    @Mode
    fun getMode(): Int = appOpsManager.checkOpNoThrow(op, app.uid, app.packageName)

    private val _mode = object : MutableLiveData<Int>() {
        override fun onActive() {
            postValue(getMode())
        }

        override fun onInactive() {
        }
    }
}
