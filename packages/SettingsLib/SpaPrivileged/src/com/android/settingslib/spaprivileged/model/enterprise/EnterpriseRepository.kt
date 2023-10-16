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

package com.android.settingslib.spaprivileged.model.enterprise

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResources.Strings.Settings.PERSONAL_CATEGORY_HEADER
import android.app.admin.DevicePolicyResources.Strings.Settings.WORK_CATEGORY_HEADER
import android.content.Context
import com.android.settingslib.spaprivileged.R

class EnterpriseRepository(private val context: Context) {
    private val resources by lazy {
        checkNotNull(context.getSystemService(DevicePolicyManager::class.java)).resources
    }

    fun getEnterpriseString(updatableStringId: String, resId: Int): String =
        checkNotNull(resources.getString(updatableStringId) { context.getString(resId) })

    fun getProfileTitle(isManagedProfile: Boolean): String = if (isManagedProfile) {
        getEnterpriseString(WORK_CATEGORY_HEADER, R.string.category_work)
    } else {
        getEnterpriseString(PERSONAL_CATEGORY_HEADER, R.string.category_personal)
    }
}
