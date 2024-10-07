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

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.ComposableLockscreenSceneBlueprint
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.notifications.ui.composable.NotificationLockscreenScrim
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationLockscreenScrimViewModel

/**
 * Renders the content of the lockscreen.
 *
 * This is separate from the [LockscreenScene] because it's meant to support usage of this UI from
 * outside the scene container framework.
 */
class LockscreenContent(
    private val viewModelFactory: LockscreenContentViewModel.Factory,
    private val notificationScrimViewModelFactory: NotificationLockscreenScrimViewModel.Factory,
    private val blueprints: Set<@JvmSuppressWildcards ComposableLockscreenSceneBlueprint>,
    private val clockInteractor: KeyguardClockInteractor,
) {
    private val blueprintByBlueprintId: Map<String, ComposableLockscreenSceneBlueprint> by lazy {
        blueprints.associateBy { it.id }
    }

    @Composable
    fun SceneScope.Content(modifier: Modifier = Modifier) {
        val viewModel =
            rememberViewModel("LockscreenContent-viewModel") { viewModelFactory.create() }
        val notificationLockscreenScrimViewModel =
            rememberViewModel("LockscreenContent-scrimViewModel") {
                notificationScrimViewModelFactory.create()
            }
        val isContentVisible: Boolean by viewModel.isContentVisible.collectAsStateWithLifecycle()
        if (!isContentVisible) {
            // If the content isn't supposed to be visible, show a large empty box as it's needed
            // for scene transition animations (can't just skip rendering everything or shared
            // elements won't have correct final/initial bounds from animating in and out of the
            // lockscreen scene).
            Box(modifier)
            return
        }

        val coroutineScope = rememberCoroutineScope()
        val blueprintId by viewModel.blueprintId(coroutineScope).collectAsStateWithLifecycle()
        val view = LocalView.current
        DisposableEffect(view) {
            clockInteractor.clockEventController.registerListeners(view)

            onDispose { clockInteractor.clockEventController.unregisterListeners() }
        }

        val blueprint = blueprintByBlueprintId[blueprintId] ?: return
        with(blueprint) {
            Content(viewModel, modifier.sysuiResTag("keyguard_root_view"))
            NotificationLockscreenScrim(notificationLockscreenScrimViewModel)
        }
    }
}
