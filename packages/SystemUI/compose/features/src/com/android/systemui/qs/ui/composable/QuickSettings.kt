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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.TransitionState
import com.android.compose.theme.colorAttr
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.qs.ui.adapter.QSSceneAdapter.State.Companion.Collapsing
import com.android.systemui.qs.ui.adapter.QSSceneAdapter.State.Expanding
import com.android.systemui.res.R
import com.android.systemui.scene.ui.composable.Gone
import com.android.systemui.scene.ui.composable.Lockscreen
import com.android.systemui.scene.ui.composable.QuickSettings as QuickSettingsSceneKey
import com.android.systemui.scene.ui.composable.Shade

object QuickSettings {
    object Elements {
        // TODO RENAME
        val Content = ElementKey("QuickSettingsContent")
        val CollapsedGrid = ElementKey("QuickSettingsCollapsedGrid")
        val FooterActions = ElementKey("QuickSettingsFooterActions")
    }
}

private fun SceneScope.stateForQuickSettingsContent(): QSSceneAdapter.State {
    return when (val transitionState = layoutState.transitionState) {
        is TransitionState.Idle -> {
            when (transitionState.currentScene) {
                Shade -> QSSceneAdapter.State.QQS
                QuickSettingsSceneKey -> QSSceneAdapter.State.QS
                else -> QSSceneAdapter.State.CLOSED
            }
        }
        is TransitionState.Transition ->
            with(transitionState) {
                when {
                    fromScene == Shade && toScene == QuickSettingsSceneKey -> Expanding(progress)
                    fromScene == QuickSettingsSceneKey && toScene == Shade -> Collapsing(progress)
                    toScene == Shade -> QSSceneAdapter.State.QQS
                    toScene == QuickSettingsSceneKey -> QSSceneAdapter.State.QS
                    toScene == Gone -> QSSceneAdapter.State.CLOSED
                    toScene == Lockscreen -> QSSceneAdapter.State.CLOSED
                    else ->
                        error(
                            "Bad transition for QuickSettings: fromScene=$fromScene," +
                                " toScene=$toScene"
                        )
                }
            }
    }
}

/**
 * This composable will show QuickSettingsContent in the correct state (as determined by its
 * [SceneScope]).
 */
@Composable
fun SceneScope.QuickSettings(
    modifier: Modifier = Modifier,
    qsSceneAdapter: QSSceneAdapter,
) {
    val contentState = stateForQuickSettingsContent()

    MovableElement(
        key = QuickSettings.Elements.Content,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 300.dp)
    ) {
        content { QuickSettingsContent(qsSceneAdapter = qsSceneAdapter, contentState) }
    }
}

@Composable
private fun QuickSettingsContent(
    qsSceneAdapter: QSSceneAdapter,
    state: QSSceneAdapter.State,
    modifier: Modifier = Modifier,
) {
    val qsView by qsSceneAdapter.qsView.collectAsState(null)
    val isCustomizing by qsSceneAdapter.isCustomizing.collectAsState()
    QuickSettingsTheme {
        val context = LocalContext.current

        LaunchedEffect(key1 = context) {
            if (qsView == null) {
                qsSceneAdapter.inflate(context)
            }
        }
        qsView?.let { view ->
            Box(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .then(
                            if (isCustomizing) {
                                Modifier.fillMaxHeight()
                            } else {
                                Modifier.wrapContentHeight()
                            }
                        )
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().background(colorAttr(R.attr.underSurface)),
                    factory = { _ ->
                        qsSceneAdapter.setState(state)
                        view
                    },
                    update = { qsSceneAdapter.setState(state) }
                )
            }
        }
    }
}
