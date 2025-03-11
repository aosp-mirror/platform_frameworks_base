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

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.MovableElementContentPicker
import com.android.compose.animation.scene.MovableElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.ValueKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.modifiers.thenIf
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.qs.ui.adapter.QSSceneAdapter.State.Companion.Collapsing
import com.android.systemui.qs.ui.adapter.QSSceneAdapter.State.Expanding
import com.android.systemui.qs.ui.adapter.QSSceneAdapter.State.UnsquishingQQS
import com.android.systemui.qs.ui.adapter.QSSceneAdapter.State.UnsquishingQS
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes

object QuickSettings {
    private val SCENES = setOf(Scenes.QuickSettings, Scenes.Shade)

    object Elements {
        val Content =
            MovableElementKey(
                "QuickSettingsContent",
                contentPicker = MovableElementContentPicker(SCENES),
            )
        val QuickQuickSettings = ElementKey("QuickQuickSettings")
        val SplitShadeQuickSettings = ElementKey("SplitShadeQuickSettings")
        val FooterActions = ElementKey("QuickSettingsFooterActions")
    }

    object SharedValues {
        val TilesSquishiness = ValueKey("QuickSettingsTileSquishiness")

        object SquishinessValues {
            val Default = 1f
            val LockscreenSceneStarting = 0f
            val GoneSceneStarting = 0.3f
        }

        val MediaLandscapeTopOffset = ValueKey("MediaLandscapeTopOffset")

        object MediaOffset {
            // Brightness
            val InQS = 60.dp
            val Default = 0.dp

            @Composable
            fun inQqs(isMediaInRow: Boolean): Dp {
                return if (isMediaInRow) {
                    // Tiles are laid out in a center of a container, that has this
                    // margin on the bottom. This compensates this margin, so that the Media
                    // Carousel can be properly centered
                    -dimensionResource(id = R.dimen.qqs_layout_padding_bottom) / 2
                } else {
                    0.dp
                }
            }
        }
    }
}

private fun SceneScope.stateForQuickSettingsContent(
    isSplitShade: Boolean,
    squishiness: () -> Float = { QuickSettings.SharedValues.SquishinessValues.Default },
): QSSceneAdapter.State {
    return when (val transitionState = layoutState.transitionState) {
        is TransitionState.Idle -> {
            when (transitionState.currentScene) {
                Scenes.Shade ->
                    QSSceneAdapter.State.QQS.takeUnless { isSplitShade } ?: QSSceneAdapter.State.QS
                Scenes.QuickSettings -> QSSceneAdapter.State.QS
                else -> QSSceneAdapter.State.CLOSED
            }
        }
        is TransitionState.Transition.ChangeScene ->
            with(transitionState) {
                when {
                    isSplitShade -> UnsquishingQS(squishiness)
                    fromScene == Scenes.Shade && toScene == Scenes.QuickSettings -> {
                        Expanding { progress }
                    }
                    fromScene == Scenes.QuickSettings && toScene == Scenes.Shade -> {
                        Collapsing { progress }
                    }
                    fromContent == Scenes.Shade || toContent == Scenes.Shade -> {
                        UnsquishingQQS(squishiness)
                    }
                    fromContent == Scenes.QuickSettings || toContent == Scenes.QuickSettings -> {
                        QSSceneAdapter.State.QS
                    }
                    else ->
                        // We are not in a transition between states that have QS, so just make
                        // sure it's closed. This could be an issue if going from SplitShade to
                        // a folded device.
                        QSSceneAdapter.State.CLOSED
                }
            }
        is TransitionState.Transition.OverlayTransition ->
            error("Bad transition for QuickSettings scene: overlays not supported")
    }
}

/**
 * This composable will show QuickSettingsContent in the correct state (as determined by its
 * [SceneScope]).
 *
 * If adding to scenes not in:
 * * QuickSettingsScene
 * * ShadeScene
 *
 * amend:
 * * [stateForQuickSettingsContent],
 * * [QuickSettings.SCENES],
 * * this doc.
 */
@Composable
fun SceneScope.QuickSettings(
    qsSceneAdapter: QSSceneAdapter,
    heightProvider: () -> Int,
    isSplitShade: Boolean,
    modifier: Modifier = Modifier,
    squishiness: () -> Float = { QuickSettings.SharedValues.SquishinessValues.Default },
) {
    val contentState = { stateForQuickSettingsContent(isSplitShade, squishiness) }

    // Note: We use derivedStateOf {} here because isClosing() is reading the current transition
    // progress and we don't want to recompose this scene each time the progress has changed.
    val isClosing by remember(layoutState) { derivedStateOf { isClosing(layoutState) } }

    if (isClosing) {
        DisposableEffect(Unit) {
            onDispose { qsSceneAdapter.setState(QSSceneAdapter.State.CLOSED) }
        }
    }

    MovableElement(
        key = QuickSettings.Elements.Content,
        modifier =
            modifier.sysuiResTag("quick_settings_panel").fillMaxWidth().layout {
                measurable,
                constraints ->
                val placeable = measurable.measure(constraints)
                // Use the height of the correct view based on the scene it is being composed in
                val height = heightProvider().coerceAtLeast(0)

                layout(placeable.width, height) { placeable.placeRelative(0, 0) }
            },
    ) {
        content { QuickSettingsContent(qsSceneAdapter = qsSceneAdapter, contentState) }
    }
}

private fun isClosing(layoutState: SceneTransitionLayoutState): Boolean {
    val transitionState = layoutState.transitionState
    return transitionState is TransitionState.Transition &&
        !(layoutState.isTransitioning(to = Scenes.Shade) ||
            layoutState.isTransitioning(to = Scenes.QuickSettings)) &&
        transitionState.progress >= 0.9f // almost done closing
}

@Composable
private fun QuickSettingsContent(
    qsSceneAdapter: QSSceneAdapter,
    state: () -> QSSceneAdapter.State,
    modifier: Modifier = Modifier,
) {
    val qsView by qsSceneAdapter.qsView.collectAsStateWithLifecycle()
    val isCustomizing by qsSceneAdapter.isCustomizerShowing.collectAsStateWithLifecycle()
    QuickSettingsTheme {
        val context = LocalContext.current

        LaunchedEffect(context) {
            if (qsView == null) {
                qsSceneAdapter.inflate(context)
            }
        }
        qsView?.let { view ->
            Box(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .thenIf(isCustomizing) { Modifier.fillMaxHeight() }
                        .drawWithContent {
                            qsSceneAdapter.applyLatestExpansionAndSquishiness()
                            drawContent()
                        }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        qsSceneAdapter.setState(state())
                        FrameLayout(context).apply {
                            (view.parent as? ViewGroup)?.removeView(view)
                            addView(view)
                        }
                    },
                    // When the view changes (e.g. due to a theme change), this will be
                    // recomposed
                    // if needed and the new view will be attached to the FrameLayout here.
                    update = {
                        qsSceneAdapter.setState(state())
                        if (view.parent != it) {
                            it.removeAllViews()
                            (view.parent as? ViewGroup)?.removeView(view)
                            it.addView(view)
                        }
                    },
                    onRelease = { it.removeAllViews() },
                )
            }
        }
    }
}
