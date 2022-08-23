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

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

object PackageManagers {
    fun getPackageInfoAsUser(packageName: String, userId: Int): PackageInfo =
        PackageManager.getPackageInfoAsUserCached(packageName, 0, userId)

    fun getApplicationInfoAsUser(packageName: String, userId: Int): ApplicationInfo =
        PackageManager.getApplicationInfoAsUserCached(packageName, 0, userId)
}
