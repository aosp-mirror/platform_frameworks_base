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

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.keyguard.qualifiers.KeyguardRootView
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.notifications.ui.composable.NotificationStack
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Renders the content of the lockscreen.
 *
 * This is different from [LockscreenContent] (which is pure compose) and uses a view-based
 * implementation of the lockscreen scene content that relies on [KeyguardRootView].
 *
 * TODO(b/316211368): remove this once [LockscreenContent] is feature complete.
 */
class ViewBasedLockscreenContent
@Inject
constructor(
    private val viewModel: LockscreenSceneViewModel,
    @KeyguardRootView private val viewProvider: () -> @JvmSuppressWildcards View,
) {
    @Composable
    fun SceneScope.Content(
        modifier: Modifier = Modifier,
    ) {
        fun findSettingsMenu(): View {
            return viewProvider().requireViewById(R.id.keyguard_settings_button)
        }

        LockscreenLongPress(
            viewModel = viewModel.longPress,
            modifier = modifier,
        ) { onSettingsMenuPlaced ->
            AndroidView(
                factory = { _ ->
                    val keyguardRootView = viewProvider()
                    // Remove the KeyguardRootView from any parent it might already have in legacy
                    // code just in case (a view can't have two parents).
                    (keyguardRootView.parent as? ViewGroup)?.removeView(keyguardRootView)
                    keyguardRootView
                },
                modifier = Modifier.fillMaxSize(),
            )

            val notificationStackPosition by
                viewModel.keyguardRoot.notificationBounds.collectAsState()

            Layout(
                modifier =
                    Modifier.fillMaxSize().onPlaced {
                        val settingsMenuView = findSettingsMenu()
                        onSettingsMenuPlaced(
                            if (settingsMenuView.isVisible) {
                                val bounds = Rect()
                                settingsMenuView.getHitRect(bounds)
                                bounds.toComposeRect()
                            } else {
                                null
                            }
                        )
                    },
                content = {
                    NotificationStack(
                        viewModel = viewModel.notifications,
                        isScrimVisible = false,
                    )
                }
            ) { measurables, constraints ->
                check(measurables.size == 1)
                val height = notificationStackPosition.height.toInt()
                val childConstraints = constraints.copy(minHeight = height, maxHeight = height)
                val placeable = measurables[0].measure(childConstraints)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val start = (constraints.maxWidth - placeable.measuredWidth) / 2
                    placeable.placeRelative(x = start, y = notificationStackPosition.top.toInt())
                }
            }
        }
    }
}
