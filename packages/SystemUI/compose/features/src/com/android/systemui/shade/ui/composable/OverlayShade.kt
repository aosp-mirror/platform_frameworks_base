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

package com.android.systemui.shade.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexScenePicker
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.keyguard.ui.composable.LockscreenContent
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ui.viewmodel.OverlayShadeViewModel
import com.android.systemui.util.kotlin.getOrNull
import dagger.Lazy
import java.util.Optional

/** The overlay shade renders a lightweight shade UI container on top of a background scene. */
@Composable
fun SceneScope.OverlayShade(
    viewModel: OverlayShadeViewModel,
    horizontalArrangement: Arrangement.Horizontal,
    lockscreenContent: Lazy<Optional<LockscreenContent>>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backgroundScene by viewModel.backgroundScene.collectAsStateWithLifecycle()

    Box(modifier) {
        if (backgroundScene == Scenes.Lockscreen) {
            // Lockscreen content is optionally injected, because variants of System UI without a
            // lockscreen cannot provide it.
            val lockscreenContentOrNull = lockscreenContent.get().getOrNull()
            lockscreenContentOrNull?.apply { Content(Modifier.fillMaxSize()) }
        }

        Scrim(onClicked = viewModel::onScrimClicked)

        Row(
            modifier = Modifier.fillMaxSize().padding(OverlayShade.Dimensions.ScrimContentPadding),
            horizontalArrangement = horizontalArrangement,
        ) {
            Panel(content = content)
        }
    }
}

@Composable
private fun SceneScope.Scrim(
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier =
            modifier
                .element(OverlayShade.Elements.Scrim)
                .fillMaxSize()
                .background(OverlayShade.Colors.ScrimBackground)
                .clickable(onClick = onClicked, interactionSource = null, indication = null)
    )
}

@Composable
private fun SceneScope.Panel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .width(OverlayShade.Dimensions.PanelWidth)
                .clip(OverlayShade.Shapes.RoundedCornerPanel)
    ) {
        Spacer(
            modifier =
                Modifier.element(OverlayShade.Elements.PanelBackground)
                    .matchParentSize()
                    .background(
                        color = OverlayShade.Colors.PanelBackground,
                        shape = OverlayShade.Shapes.RoundedCornerPanel,
                    ),
        )

        // This content is intentionally rendered as a separate element from the background in order
        // to allow for more flexibility when defining transitions.
        content()
    }
}

object OverlayShade {
    object Elements {
        val Scrim = ElementKey("OverlayShadeScrim", scenePicker = LowestZIndexScenePicker)
        val PanelBackground =
            ElementKey("OverlayShadePanelBackground", scenePicker = LowestZIndexScenePicker)
    }

    object Colors {
        val ScrimBackground = Color(0, 0, 0, alpha = 255 / 3)
        val PanelBackground: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
    }

    object Dimensions {
        val ScrimContentPadding = 16.dp
        val PanelCornerRadius = 46.dp
        // TODO(b/338033836): This width should not be fixed.
        val PanelWidth = 390.dp
    }

    object Shapes {
        val RoundedCornerPanel = RoundedCornerShape(Dimensions.PanelCornerRadius)
    }
}
