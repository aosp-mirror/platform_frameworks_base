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

import android.app.admin.DevicePolicyResources.Strings.Settings.PERSONAL_CATEGORY_HEADER
import android.app.admin.DevicePolicyResources.Strings.Settings.PRIVATE_CATEGORY_HEADER
import android.app.admin.DevicePolicyResources.Strings.Settings.WORK_CATEGORY_HEADER
import android.content.Context
import android.content.pm.UserInfo
import com.android.settingslib.R
import com.android.settingslib.spaprivileged.framework.common.devicePolicyManager

interface IEnterpriseRepository {
    fun getEnterpriseString(updatableStringId: String, resId: Int): String
}

class EnterpriseRepository(private val context: Context) : IEnterpriseRepository {
    private val resources by lazy { context.devicePolicyManager.resources }

    override fun getEnterpriseString(updatableStringId: String, resId: Int): String =
        checkNotNull(resources.getString(updatableStringId) { context.getString(resId) })

    fun getProfileTitle(userInfo: UserInfo): String = if (userInfo.isManagedProfile) {
        getEnterpriseString(WORK_CATEGORY_HEADER, R.string.category_work)
    } else if (userInfo.isPrivateProfile) {
        getEnterpriseString(PRIVATE_CATEGORY_HEADER, R.string.category_private)
    } else {
        getEnterpriseString(PERSONAL_CATEGORY_HEADER, R.string.category_personal)
    }
}
