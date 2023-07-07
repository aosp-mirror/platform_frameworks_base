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

package com.android.settingslib.spa.widget.preference

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.ui.LinearProgressBar
import com.android.settingslib.spa.widget.ui.SettingsTitle

/**
 * The widget model for [ProgressBarPreference] widget.
 */
interface ProgressBarPreferenceModel {
    /**
     * The title of this [ProgressBarPreference].
     */
    val title: String

    /**
     * The progress fraction of the ProgressBar. Should be float in range [0f, 1f]
     */
    val progress: Float

    /**
     * The icon image for [ProgressBarPreference]. If not specified, hides the icon by default.
     */
    val icon: ImageVector?
        get() = null

    /**
     * The height of the ProgressBar.
     */
    val height: Float
        get() = 4f

    /**
     * Indicates whether to use rounded corner for the progress bars.
     */
    val roundedCorner: Boolean
        get() = true
}

/**
 * Progress bar preference widget.
 *
 * Data is provided through [ProgressBarPreferenceModel].
 */
@Composable
fun ProgressBarPreference(model: ProgressBarPreferenceModel) {
    ProgressBarPreference(
        title = model.title,
        progress = model.progress,
        icon = model.icon,
        height = model.height,
        roundedCorner = model.roundedCorner,
    )
}

/**
 * Progress bar with data preference widget.
 */
@Composable
fun ProgressBarWithDataPreference(model: ProgressBarPreferenceModel, data: String) {
    val icon = model.icon
    ProgressBarWithDataPreference(
        title = model.title,
        data = data,
        progress = model.progress,
        icon = if (icon != null) ({
            Icon(imageVector = icon, contentDescription = null)
        }) else null,
        height = model.height,
        roundedCorner = model.roundedCorner,
    )
}

@Composable
internal fun ProgressBarPreference(
    title: String,
    progress: Float,
    icon: ImageVector? = null,
    height: Float = 4f,
    roundedCorner: Boolean = true,
) {
    BaseLayout(
        title = title,
        subTitle = {
            LinearProgressBar(progress, height, roundedCorner)
        },
        icon = if (icon != null) ({
            Icon(imageVector = icon, contentDescription = null)
        }) else null,
    )
}


@Composable
internal fun ProgressBarWithDataPreference(
    title: String,
    data: String,
    progress: Float,
    icon: (@Composable () -> Unit)? = null,
    height: Float = 4f,
    roundedCorner: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = SettingsDimension.itemPaddingEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaseIcon(icon, Modifier, SettingsDimension.itemPaddingStart)
        TitleWithData(
            title = title,
            data = data,
            subTitle = {
                LinearProgressBar(progress, height, roundedCorner)
            },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = SettingsDimension.itemPaddingVertical),
        )
    }
}

@Composable
private fun TitleWithData(
    title: String,
    data: String,
    subTitle: @Composable () -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        Row {
            Box(modifier = Modifier.weight(1f)) {
                SettingsTitle(title)
            }
            Text(
                text = data,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        subTitle()
    }
}
