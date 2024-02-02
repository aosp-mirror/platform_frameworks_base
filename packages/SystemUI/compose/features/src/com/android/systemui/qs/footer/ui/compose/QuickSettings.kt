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
 *
 */

package com.android.systemui.qs.footer.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope

object QuickSettings {
    object Elements {
        // TODO RENAME
        val Content = ElementKey("QuickSettingsContent")
        val CollapsedGrid = ElementKey("QuickSettingsCollapsedGrid")
        val FooterActions = ElementKey("QuickSettingsFooterActions")
    }
}

@Composable
fun SceneScope.QuickSettings(
    modifier: Modifier = Modifier,
) {
    // TODO(b/272780058): implement.
    Column(
        modifier =
            modifier
                .element(QuickSettings.Elements.Content)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 300.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(16.dp),
    ) {
        Text(
            text = "Quick settings grid",
            modifier =
                Modifier.element(QuickSettings.Elements.CollapsedGrid)
                    .align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "QS footer actions",
            modifier =
                Modifier.element(QuickSettings.Elements.FooterActions)
                    .align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
