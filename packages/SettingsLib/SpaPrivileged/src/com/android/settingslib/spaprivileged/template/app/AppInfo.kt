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
import android.content.pm.PackageInfo
import android.text.BidiFormatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsTitle
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.rememberAppRepository

class AppInfoProvider(private val packageInfo: PackageInfo) {
    @Composable
    fun AppInfo(displayVersion: Boolean = false, isClonedAppPage: Boolean = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SettingsDimension.itemPaddingStart,
                    vertical = SettingsDimension.itemPaddingVertical,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val app = packageInfo.applicationInfo
            Box(modifier = Modifier.padding(SettingsDimension.itemPaddingAround)) {
                AppIcon(app = app, size = SettingsDimension.appIconInfoSize)
            }
            AppLabel(app, isClonedAppPage)
            InstallType(app)
            if (displayVersion) AppVersion()
        }
    }

    @Composable
    private fun InstallType(app: ApplicationInfo) {
        if (!app.isInstantApp) return
        Spacer(modifier = Modifier.height(4.dp))
        SettingsBody(stringResource(R.string.install_type_instant))
    }

    @Composable
    private fun AppVersion() {
        if (packageInfo.versionName == null) return
        Spacer(modifier = Modifier.height(4.dp))
        SettingsBody(packageInfo.versionName)
    }

    @Composable
    fun FooterAppVersion() {
        if (packageInfo.versionName == null) return
        Divider()
        Box(modifier = Modifier.padding(SettingsDimension.itemPadding)) {
            val versionName = BidiFormatter.getInstance().unicodeWrap(packageInfo.versionName)
            SettingsBody(stringResource(R.string.version_text, versionName))
        }
    }
}

@Composable
internal fun AppIcon(app: ApplicationInfo, size: Dp) {
    val appRepository = rememberAppRepository()
    Image(
        painter = rememberDrawablePainter(appRepository.produceIcon(app).value),
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}

@Composable
internal fun AppLabel(app: ApplicationInfo, isClonedAppPage: Boolean = false) {
    val appRepository = rememberAppRepository()
    SettingsTitle(title = appRepository.produceLabel(app, isClonedAppPage), useMediumWeight = true)
}
