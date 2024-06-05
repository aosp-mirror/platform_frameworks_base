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

package com.android.server.permission.access.util

import android.content.ApexEnvironment
import android.os.UserHandle
import java.io.File

object PermissionApex {
    private const val MODULE_NAME = "com.android.permission"

    /** @see ApexEnvironment.getDeviceProtectedDataDir */
    val systemDataDirectory: File
        get() = apexEnvironment.deviceProtectedDataDir

    /** @see ApexEnvironment.getDeviceProtectedDataDirForUser */
    fun getUserDataDirectory(userId: Int): File =
        apexEnvironment.getDeviceProtectedDataDirForUser(UserHandle.of(userId))

    private val apexEnvironment: ApexEnvironment
        get() = ApexEnvironment.getApexEnvironment(MODULE_NAME)
}
