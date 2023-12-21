/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.parsing

import android.content.pm.ApplicationInfo
import android.util.ArraySet
import com.android.internal.pm.parsing.PackageParser2
import java.io.File

class TestPackageParser2(var cacheDir: File? = null) : PackageParser2(
        null /* separateProcesses */, null /* displayMetrics */,
    cacheDir?.let { PackageCacher(cacheDir) }, object : PackageParser2.Callback() {
    override fun isChangeEnabled(changeId: Long, appInfo: ApplicationInfo): Boolean {
        return true
    }

    override fun hasFeature(feature: String): Boolean {
        // Assume the device doesn't support anything. This will affect permission parsing
        // and will force <uses-permission/> declarations to include all requiredNotFeature
        // permissions and exclude all requiredFeature permissions. This mirrors the old
        // behavior.
        return false
    }

    override fun getHiddenApiWhitelistedApps() = ArraySet<String>()
    override fun getInstallConstraintsAllowlist() = ArraySet<String>()
})
