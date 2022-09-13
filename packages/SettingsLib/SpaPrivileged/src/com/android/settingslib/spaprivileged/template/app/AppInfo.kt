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

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsTitle
import com.android.settingslib.spaprivileged.model.app.PackageManagers
import com.android.settingslib.spaprivileged.model.app.rememberAppRepository

@Composable
fun AppInfo(packageName: String, userId: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsDimension.itemPaddingStart,
                vertical = SettingsDimension.itemPaddingVertical,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val packageInfo =
            remember { PackageManagers.getPackageInfoAsUser(packageName, userId) } ?: return
        Box(modifier = Modifier.padding(SettingsDimension.itemPaddingAround)) {
            AppIcon(app = packageInfo.applicationInfo, size = SettingsDimension.appIconInfoSize)
        }
        AppLabel(packageInfo.applicationInfo)
        AppVersion(packageInfo.versionName)
    }
}

@Composable
private fun AppVersion(versionName: String?) {
    if (versionName == null) return
    Spacer(modifier = Modifier.height(4.dp))
    SettingsBody(versionName)
}

@Composable
fun AppIcon(app: ApplicationInfo, size: Dp) {
    val appRepository = rememberAppRepository()
    Image(
        painter = rememberDrawablePainter(appRepository.produceIcon(app).value),
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}

@Composable
fun AppLabel(app: ApplicationInfo) {
    val appRepository = rememberAppRepository()
    SettingsTitle(appRepository.produceLabel(app))
}
