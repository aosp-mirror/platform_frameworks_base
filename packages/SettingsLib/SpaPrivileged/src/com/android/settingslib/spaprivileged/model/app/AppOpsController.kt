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

package com.android.settingslib.spaprivileged.model.app

import android.app.AppOpsManager
import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_ERRORED
import android.app.AppOpsManager.Mode
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.settingslib.spaprivileged.framework.common.appOpsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface IAppOpsController {
    val modeFlow: Flow<Int>
    val isAllowed: Flow<Boolean>
        get() = modeFlow.map { it == MODE_ALLOWED }

    fun setAllowed(allowed: Boolean)

    @Mode fun getMode(): Int
}

data class AppOps(
    val op: Int,
    val modeForNotAllowed: Int = MODE_ERRORED,

    /**
     * Use AppOpsManager#setUidMode() instead of AppOpsManager#setMode() when set allowed.
     *
     * Security or privacy related app-ops should be set with setUidMode() instead of setMode().
     */
    val setModeByUid: Boolean = false,
)

class AppOpsController(
    context: Context,
    private val app: ApplicationInfo,
    private val appOps: AppOps,
) : IAppOpsController {
    private val appOpsManager = context.appOpsManager
    private val packageManager = context.packageManager
    override val modeFlow = appOpsManager.opModeFlow(appOps.op, app)

    override fun setAllowed(allowed: Boolean) {
        val mode = if (allowed) MODE_ALLOWED else appOps.modeForNotAllowed

        if (appOps.setModeByUid) {
            appOpsManager.setUidMode(appOps.op, app.uid, mode)
        } else {
            appOpsManager.setMode(appOps.op, app.uid, app.packageName, mode)
        }

        val permission = AppOpsManager.opToPermission(appOps.op)
        if (permission != null) {
            packageManager.updatePermissionFlags(permission, app.packageName,
                    PackageManager.FLAG_PERMISSION_USER_SET,
                    PackageManager.FLAG_PERMISSION_USER_SET,
                    UserHandle.getUserHandleForUid(app.uid))
        }
    }

    @Mode
    override fun getMode(): Int = appOpsManager.getOpMode(appOps.op, app)
}
