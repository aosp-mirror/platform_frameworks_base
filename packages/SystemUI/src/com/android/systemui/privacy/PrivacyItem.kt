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
import android.os.UserHandle
import android.util.IconDrawableFactory
import com.android.systemui.R

typealias Privacy = PrivacyType

enum class PrivacyType(private val nameId: Int, val iconId: Int) {
    // This is uses the icons used by the corresponding permission groups in the AndroidManifest
    TYPE_CAMERA(R.string.privacy_type_camera,
            com.android.internal.R.drawable.perm_group_camera),
    TYPE_MICROPHONE(R.string.privacy_type_microphone,
            com.android.internal.R.drawable.perm_group_microphone),
    TYPE_LOCATION(R.string.privacy_type_location,
            com.android.internal.R.drawable.perm_group_location);

    fun getName(context: Context) = context.resources.getString(nameId)

    fun getIcon(context: Context) = context.resources.getDrawable(iconId, context.theme)
}

data class PrivacyItem(
    val privacyType: PrivacyType,
    val application: PrivacyApplication
)

data class PrivacyApplication(val packageName: String, val uid: Int, val context: Context)
    : Comparable<PrivacyApplication> {

    override fun compareTo(other: PrivacyApplication): Int {
        return applicationName.compareTo(other.applicationName)
    }

    private val applicationInfo: ApplicationInfo? by lazy {
        try {
            val userHandle = UserHandle.getUserHandleForUid(uid)
            context.createPackageContextAsUser(packageName, 0, userHandle).getPackageManager()
                    .getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
    val icon: Drawable by lazy {
        applicationInfo?.let {
            try {
                val iconFactory = IconDrawableFactory.newInstance(context, true)
                iconFactory.getBadgedIcon(it, UserHandle.getUserId(uid))
            } catch (_: Exception) {
                null
            }
        } ?: context.getDrawable(android.R.drawable.sym_def_app_icon)
    }

    val applicationName: String by lazy {
        applicationInfo?.let {
            context.packageManager.getApplicationLabel(it) as String
        } ?: packageName
    }

    override fun toString() = "PrivacyApplication(packageName=$packageName, uid=$uid)"
}
