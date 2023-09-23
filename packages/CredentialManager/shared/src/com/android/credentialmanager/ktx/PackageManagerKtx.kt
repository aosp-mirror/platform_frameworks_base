/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.ktx

import android.content.pm.PackageManager
import android.text.TextUtils
import android.util.Log
import com.android.credentialmanager.TAG

fun PackageManager.appLabel(appPackageName: String): String? =
    try {
        val pkgInfo = this.getPackageInfo(appPackageName, PackageManager.PackageInfoFlags.of(0))
        val applicationInfo = checkNotNull(pkgInfo.applicationInfo)
        applicationInfo.loadSafeLabel(
            this, 0f,
            TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
        ).toString()
    } catch (e: Exception) {
        Log.e(TAG, "Caller app not found", e)
        null
    }
