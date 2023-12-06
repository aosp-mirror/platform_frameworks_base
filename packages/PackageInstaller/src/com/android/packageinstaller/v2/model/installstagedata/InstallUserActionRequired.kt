/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model.installstagedata

import android.graphics.drawable.Drawable
import com.android.packageinstaller.v2.model.PackageUtil

class InstallUserActionRequired(
    val actionReason: Int,
    private val appSnippet: PackageUtil.AppSnippet? = null,
    val isAppUpdating: Boolean = false,
    val dialogMessage: String? = null
) : InstallStage() {

    override val stageCode = STAGE_USER_ACTION_REQUIRED

    val appIcon: Drawable?
        get() = appSnippet?.icon

    val appLabel: String?
        get() = appSnippet?.let { appSnippet.label as String? }

    companion object {
        const val USER_ACTION_REASON_UNKNOWN_SOURCE = 0
        const val USER_ACTION_REASON_ANONYMOUS_SOURCE = 1
        const val USER_ACTION_REASON_INSTALL_CONFIRMATION = 2
    }
}
