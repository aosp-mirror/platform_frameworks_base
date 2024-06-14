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

package com.android.systemui.bouncer.ui.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.ComposableScene
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Bouncer {
    object Elements {
        val Background = ElementKey("BouncerBackground")
        val Content = ElementKey("BouncerContent")
    }
}

/** The bouncer scene displays authentication challenges like PIN, password, or pattern. */
@SysUISingleton
class BouncerScene
@Inject
constructor(
    private val viewModel: BouncerViewModel,
    private val dialogFactory: BouncerDialogFactory,
) : ComposableScene {
    override val key = Scenes.Bouncer

    override val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        MutableStateFlow(
                mapOf(
                    Back to UserActionResult(Scenes.Lockscreen),
                    Swipe(SwipeDirection.Down) to UserActionResult(Scenes.Lockscreen),
                )
            )
            .asStateFlow()

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) = BouncerScene(viewModel, dialogFactory, modifier)
}

@Composable
private fun SceneScope.BouncerScene(
    viewModel: BouncerViewModel,
    dialogFactory: BouncerDialogFactory,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.surface

    Box(modifier) {
        Canvas(Modifier.element(Bouncer.Elements.Background).fillMaxSize()) {
            drawRect(color = backgroundColor)
        }

        // Separate the bouncer content into a reusable composable that doesn't have any SceneScope
        // dependencies
        BouncerContent(
            viewModel,
            dialogFactory,
            Modifier.element(Bouncer.Elements.Content).fillMaxSize()
        )
    }
}
