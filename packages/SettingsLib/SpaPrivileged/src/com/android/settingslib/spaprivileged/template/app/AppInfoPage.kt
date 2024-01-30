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

package com.android.settingslib.spaprivileged.template.app

import android.content.pm.PackageInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Footer
import com.android.settingslib.spaprivileged.model.app.IPackageManagers

@Composable
fun AppInfoPage(
    title: String,
    packageName: String,
    userId: Int,
    footerContent: @Composable () -> Unit,
    packageManagers: IPackageManagers,
    content: @Composable PackageInfo.() -> Unit,
) {
    val packageInfo = remember(packageName, userId) {
        packageManagers.getPackageInfoAsUser(packageName, userId)
    } ?: return
    RegularScaffold(title = title) {
        remember(packageInfo) { AppInfoProvider(packageInfo) }.AppInfo(displayVersion = true)

        packageInfo.content()

        Footer(footerContent)
    }
}
