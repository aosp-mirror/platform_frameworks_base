/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.privacy

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.android.systemui.R

typealias Privacy = PrivacyType

enum class PrivacyType(val nameId: Int, val iconId: Int) {
    TYPE_CAMERA(R.string.privacy_type_camera, R.drawable.stat_sys_camera),
    TYPE_LOCATION(R.string.privacy_type_location, R.drawable.stat_sys_location),
    TYPE_MICROPHONE(R.string.privacy_type_microphone, R.drawable.stat_sys_mic_none);

    fun getName(context: Context) = context.resources.getString(nameId)

    fun getIcon(context: Context) = context.resources.getDrawable(iconId, context.theme)
}

data class PrivacyItem(
    val privacyType: PrivacyType,
    val application: PrivacyApplication
)

data class PrivacyApplication(val packageName: String, val context: Context)
    : Comparable<PrivacyApplication> {

    override fun compareTo(other: PrivacyApplication): Int {
        return applicationName.compareTo(other.applicationName)
    }

    var icon: Drawable? = null
    var applicationName: String

    init {
        try {
            val app: ApplicationInfo = context.packageManager
                    .getApplicationInfo(packageName, 0)
            icon = context.packageManager.getApplicationIcon(app)
            applicationName = context.packageManager.getApplicationLabel(app) as String
        } catch (e: PackageManager.NameNotFoundException) {
            applicationName = packageName
        }
    }
}
