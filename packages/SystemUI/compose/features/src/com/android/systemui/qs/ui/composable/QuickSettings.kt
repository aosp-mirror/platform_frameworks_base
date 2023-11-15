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

package com.android.systemui.qs.ui.composable

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.theme.colorAttr
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.res.R

object QuickSettings {
    object Elements {
        // TODO RENAME
        val Content = ElementKey("QuickSettingsContent")
        val CollapsedGrid = ElementKey("QuickSettingsCollapsedGrid")
        val FooterActions = ElementKey("QuickSettingsFooterActions")
    }
}

@Composable
private fun QuickSettingsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val themedContext =
        remember(context) { ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings) }
    CompositionLocalProvider(LocalContext provides themedContext) { content() }
}

@Composable
fun SceneScope.QuickSettings(
    modifier: Modifier = Modifier,
    qsSceneAdapter: QSSceneAdapter,
    state: QSSceneAdapter.State
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
                .padding(1.dp),
    ) {
        QuickSettingsContent(qsSceneAdapter = qsSceneAdapter, state)
    }
}

@Composable
private fun QuickSettingsContent(
    qsSceneAdapter: QSSceneAdapter,
    state: QSSceneAdapter.State,
    modifier: Modifier = Modifier,
) {
    val qsView by qsSceneAdapter.qsView.collectAsState(null)
    QuickSettingsTheme {
        val context = LocalContext.current

        val frame by remember(context) { mutableStateOf(FrameLayout(context)) }

        LaunchedEffect(key1 = context) {
            if (qsView == null) {
                qsSceneAdapter.inflate(context, frame)
            }
        }
        qsView?.let {
            it.attachToParent(frame)
            AndroidView(
                modifier = modifier.fillMaxSize().background(colorAttr(R.attr.underSurface)),
                factory = { _ ->
                    qsSceneAdapter.setState(state)
                    frame
                },
                onRelease = { frame.removeAllViews() },
                update = { qsSceneAdapter.setState(state) }
            )
        }
    }
}

private fun View.attachToParent(parent: ViewGroup) {
    if (this.parent != null && this.parent != parent) {
        (this.parent as ViewGroup).removeView(this)
    }
    if (this.parent != parent) {
        parent.addView(
            this,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
}
