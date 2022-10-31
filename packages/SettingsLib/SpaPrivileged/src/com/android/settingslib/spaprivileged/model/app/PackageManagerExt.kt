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

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags

/**
 * Checks if a package is system module.
 */
fun PackageManager.isSystemModule(packageName: String): Boolean = try {
    getModuleInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    // Expected, not system module
    false
}

/**
 * Resolves the activity to start for a given application and action.
 */
fun PackageManager.resolveActionForApp(
    app: ApplicationInfo,
    action: String,
    flags: Int = 0,
): ActivityInfo? {
    val intent = Intent(action).apply {
        `package` = app.packageName
    }
    return resolveActivityAsUser(intent, ResolveInfoFlags.of(flags.toLong()), app.userId)
        ?.activityInfo
}
