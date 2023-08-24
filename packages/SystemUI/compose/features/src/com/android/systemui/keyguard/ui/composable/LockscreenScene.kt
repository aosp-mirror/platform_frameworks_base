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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.composable

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.qualifiers.KeyguardRootView
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The lock screen scene shows when the device is locked. */
@SysUISingleton
class LockscreenScene
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: LockscreenSceneViewModel,
    @KeyguardRootView private val viewProvider: () -> @JvmSuppressWildcards View,
) : ComposableScene {
    override val key = SceneKey.Lockscreen

    override fun destinationScenes(): StateFlow<Map<UserAction, SceneModel>> =
        viewModel.upDestinationSceneKey
            .map { pageKey -> destinationScenes(up = pageKey) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = destinationScenes(up = null)
            )

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        LockscreenScene(
            viewProvider = viewProvider,
            modifier = modifier,
        )
    }

    private fun destinationScenes(
        up: SceneKey?,
    ): Map<UserAction, SceneModel> {
        return buildMap {
            up?.let { this[UserAction.Swipe(Direction.UP)] = SceneModel(up) }
            this[UserAction.Swipe(Direction.DOWN)] = SceneModel(SceneKey.Shade)
        }
    }
}

@Composable
private fun LockscreenScene(
    viewProvider: () -> View,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { _ ->
            val keyguardRootView = viewProvider()
            // Remove the KeyguardRootView from any parent it might already have in legacy code just
            // in case (a view can't have two parents).
            (keyguardRootView.parent as? ViewGroup)?.removeView(keyguardRootView)
            keyguardRootView
        },
        update = { keyguardRootView ->
            keyguardRootView.requireViewById<View>(R.id.lock_icon_view)
        },
        modifier = modifier,
    )
}
