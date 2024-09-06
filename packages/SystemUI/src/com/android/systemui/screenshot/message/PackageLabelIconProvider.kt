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

package com.android.systemui.screenshot.message

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import javax.inject.Inject

data class LabeledIcon(
    val label: CharSequence,
    val badgedIcon: Drawable?,
)

/** An object that can fetch a label and icon for a given component. */
interface PackageLabelIconProvider {
    /**
     * @return the label and icon for the given component.
     * @throws PackageManager.NameNotFoundException if the component was not found.
     */
    suspend fun getPackageLabelIcon(
        componentName: ComponentName,
        userHandle: UserHandle
    ): LabeledIcon
}

class PackageLabelIconProviderImpl @Inject constructor(private val packageManager: PackageManager) :
    PackageLabelIconProvider {

    override suspend fun getPackageLabelIcon(
        componentName: ComponentName,
        userHandle: UserHandle
    ): LabeledIcon {
        val info =
            packageManager.getActivityInfo(componentName, PackageManager.ComponentInfoFlags.of(0L))
        val icon = packageManager.getActivityIcon(componentName)
        val badgedIcon = packageManager.getUserBadgedIcon(icon, userHandle)
        val label = info.loadLabel(packageManager)
        return LabeledIcon(label, badgedIcon)
    }
}
