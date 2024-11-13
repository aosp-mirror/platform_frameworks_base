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

package com.android.systemui.util.icons

import android.app.role.RoleManager.ROLE_ASSISTANT
import android.graphics.drawable.Icon

class FakeAppCategoryIconProvider : AppCategoryIconProvider {

    private val installedApps = mutableMapOf<String, App>()

    fun installCategoryApp(category: String, packageName: String, iconResId: Int) {
        installedApps[category] = App(packageName, iconResId)
    }

    fun installAssistantApp(packageName: String, iconResId: Int) {
        installedApps[ROLE_ASSISTANT] = App(packageName, iconResId)
    }

    override suspend fun assistantAppIcon() = categoryAppIcon(ROLE_ASSISTANT)

    override suspend fun categoryAppIcon(category: String): Icon? {
        val app = installedApps[category] ?: return null
        return Icon.createWithResource(app.packageName, app.iconResId)
    }

    private class App(val packageName: String, val iconResId: Int)
}
