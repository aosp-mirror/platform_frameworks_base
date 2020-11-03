/*
 * Copyright (C) 2020 The Android Open Source Project
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
import com.android.systemui.R

typealias Privacy = PrivacyType

enum class PrivacyType(val nameId: Int, val iconId: Int) {
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

data class PrivacyItem(val privacyType: PrivacyType, val application: PrivacyApplication)

data class PrivacyApplication(val packageName: String, val uid: Int)
