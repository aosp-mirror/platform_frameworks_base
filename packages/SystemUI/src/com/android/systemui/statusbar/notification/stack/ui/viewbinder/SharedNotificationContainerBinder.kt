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

package com.android.systemui.statusbar.notification.stack.ui.viewbinder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.shared.flexiNotifsEnabled
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/** Binds the shared notification container to its view-model. */
object SharedNotificationContainerBinder {

    @JvmStatic
    fun bind(
        view: SharedNotificationContainer,
        viewModel: SharedNotificationContainerViewModel,
        sceneContainerFlags: SceneContainerFlags,
        controller: NotificationStackScrollLayoutController,
        notificationStackSizeCalculator: NotificationStackSizeCalculator,
        @Main mainImmediateDispatcher: CoroutineDispatcher,
    ): DisposableHandle {
        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        viewModel.configurationBasedDimensions.collect {
                            view.updateConstraints(
                                useSplitShade = it.useSplitShade,
                                marginStart = it.marginStart,
                                marginTop = it.marginTop,
                                marginEnd = it.marginEnd,
                                marginBottom = it.marginBottom,
                            )

                            controller.setOverExpansion(0f)
                            controller.setOverScrollAmount(0)
                            controller.updateFooter()
                        }
                    }
                }
            }

        /*
         * For animation sensitive coroutines, immediately run just like applicationScope does
         * instead of doing a post() to the main thread. This extra delay can cause visible jitter.
         */
        val disposableHandleMainImmediate =
            view.repeatWhenAttached(mainImmediateDispatcher) {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    if (!sceneContainerFlags.flexiNotifsEnabled()) {
                        launch {
                            // Only temporarily needed, until flexi notifs go live
                            viewModel.shadeCollpaseFadeIn.collect { fadeIn ->
                                if (fadeIn) {
                                    android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                        duration = 350
                                        addUpdateListener { animation ->
                                            controller.setMaxAlphaForExpansion(
                                                animation.getAnimatedFraction()
                                            )
                                        }
                                        addListener(
                                            object : AnimatorListenerAdapter() {
                                                override fun onAnimationEnd(animation: Animator) {
                                                    viewModel.setShadeCollapseFadeInComplete(true)
                                                }
                                            }
                                        )
                                        start()
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        viewModel
                            .getMaxNotifications { space, extraShelfSpace ->
                                val shelfHeight = controller.getShelfHeight().toFloat()
                                notificationStackSizeCalculator.computeMaxKeyguardNotifications(
                                    controller.getView(),
                                    space,
                                    if (extraShelfSpace) shelfHeight else 0f,
                                    shelfHeight,
                                )
                            }
                            .collect { controller.setMaxDisplayedNotifications(it) }
                    }

                    if (!sceneContainerFlags.flexiNotifsEnabled()) {
                        launch {
                            viewModel.bounds.collect {
                                val animate =
                                    it.isAnimated || controller.isAddOrRemoveAnimationPending
                                controller.updateTopPadding(it.top, animate)
                            }
                        }
                    }

                    launch { viewModel.translationY.collect { controller.setTranslationY(it) } }

                    launch {
                        viewModel.expansionAlpha.collect { controller.setMaxAlphaForExpansion(it) }
                    }
                    launch {
                        viewModel.glanceableHubAlpha.collect {
                            controller.setMaxAlphaForGlanceableHub(it)
                        }
                    }
                }
            }

        controller.setOnHeightChangedRunnable(Runnable { viewModel.notificationStackChanged() })

        return object : DisposableHandle {
            override fun dispose() {
                disposableHandle.dispose()
                disposableHandleMainImmediate.dispose()
                controller.setOnHeightChangedRunnable(null)
            }
        }
    }
}
