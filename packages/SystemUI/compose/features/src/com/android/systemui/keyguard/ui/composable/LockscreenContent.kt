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

package com.android.systemui.keyguard.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.ComposableLockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import javax.inject.Inject

/**
 * Renders the content of the lockscreen.
 *
 * This is separate from the [LockscreenScene] because it's meant to support usage of this UI from
 * outside the scene container framework.
 */
class LockscreenContent
@Inject
constructor(
    private val viewModel: LockscreenContentViewModel,
    private val blueprints: Set<@JvmSuppressWildcards ComposableLockscreenSceneBlueprint>,
    private val clockInteractor: KeyguardClockInteractor,
) {
    private val blueprintByBlueprintId: Map<String, ComposableLockscreenSceneBlueprint> by lazy {
        blueprints.associateBy { it.id }
    }

    @Composable
    fun SceneScope.Content(
        modifier: Modifier = Modifier,
    ) {
        val coroutineScope = rememberCoroutineScope()
        val blueprintId by viewModel.blueprintId(coroutineScope).collectAsStateWithLifecycle()
        val view = LocalView.current
        DisposableEffect(view) {
            clockInteractor.clockEventController.registerListeners(view)

            onDispose { clockInteractor.clockEventController.unregisterListeners() }
        }

        val blueprint = blueprintByBlueprintId[blueprintId] ?: return
        with(blueprint) { Content(modifier) }
    }
}
