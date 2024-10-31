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

package com.android.settingslib.spa.widget.preference

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.toMediumWeight
import com.android.settingslib.spa.framework.util.annotatedStringResource

/** The widget model for [TopIntroPreference] widget. */
interface TopIntroPreferenceModel {
    /** The content of this [TopIntroPreference]. */
    val text: String

    /** The text clicked to expand this [TopIntroPreference]. */
    val expandText: String

    /** The text clicked to collapse this [TopIntroPreference]. */
    val collapseText: String

    /** The text clicked to open other resources. Should be a resource Id. */
    val labelText: Int?
}

@Composable
fun TopIntroPreference(model: TopIntroPreferenceModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        // TopIntroPreference content.
        Column(
            modifier =
                Modifier.padding(
                        horizontal = SettingsDimension.paddingExtraLarge,
                        vertical = SettingsDimension.paddingSmall,
                    )
                    .animateContentSize()
        ) {
            Text(
                text = model.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) MAX_LINE else MIN_LINE,
            )
            if (expanded) TopIntroAnnotatedText(model.labelText)
        }

        // TopIntroPreference collapse bar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(onClick = { expanded = !expanded })
                    .padding(
                        top = SettingsDimension.paddingSmall,
                        bottom = SettingsDimension.paddingLarge,
                        start = SettingsDimension.paddingExtraLarge,
                        end = SettingsDimension.paddingExtraLarge,
                    ),
        ) {
            Icon(
                imageVector =
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier =
                    Modifier.size(SettingsDimension.itemIconSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Text(
                text = if (expanded) model.collapseText else model.expandText,
                modifier = Modifier.padding(start = SettingsDimension.paddingSmall),
                style = MaterialTheme.typography.bodyLarge.toMediumWeight(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TopIntroAnnotatedText(@StringRes id: Int?) {
    if (id != null) {
        Box(
            Modifier.padding(
                top = SettingsDimension.paddingExtraSmall5,
                bottom = SettingsDimension.paddingExtraSmall5,
                end = SettingsDimension.paddingLarge,
            )
        ) {
            Text(
                text = annotatedStringResource(id),
                style = MaterialTheme.typography.bodyLarge.toMediumWeight(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Preview
@Composable
private fun TopIntroPreferencePreview() {
    TopIntroPreference(
        object : TopIntroPreferenceModel {
            override val text =
                "Additional text needed for the page. This can sit on the right side of the screen in 2 column.\n" +
                    "Example collapsed text area that you will not see until you expand this block."
            override val expandText = "Expand"
            override val collapseText = "Collapse"
            override val labelText = androidx.appcompat.R.string.abc_prepend_shortcut_label
        }
    )
}

const val MIN_LINE = 2
const val MAX_LINE = 10
