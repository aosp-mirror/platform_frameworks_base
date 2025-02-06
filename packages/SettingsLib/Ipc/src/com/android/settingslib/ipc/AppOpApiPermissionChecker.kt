/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.settingslib.ipc

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager

/** [ApiPermissionChecker] that checks if calling app has given app-op permission. */
class AppOpApiPermissionChecker<T>(private val op: Int, private val permission: String) :
    ApiPermissionChecker<T> {

    @Suppress("DEPRECATION")
    override fun hasPermission(
        application: Application,
        callingPid: Int,
        callingUid: Int,
        request: T,
    ): Boolean {
        val appOpsManager =
            application.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val pkg = application.packageManager.getNameForUid(callingUid) ?: return false
        return when (appOpsManager.noteOp(op, callingUid, pkg)) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                application.checkPermission(permission, callingPid, callingUid) ==
                    PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }
}
