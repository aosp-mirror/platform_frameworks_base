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

import android.content.Context
import android.util.TypedValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationStackAppearanceViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/** Binds the shared notification container to its view-model. */
object NotificationStackAppearanceViewBinder {
    const val SCRIM_CORNER_RADIUS = 32f

    @JvmStatic
    fun bind(
        context: Context,
        view: SharedNotificationContainer,
        viewModel: NotificationStackAppearanceViewModel,
        ambientState: AmbientState,
        controller: NotificationStackScrollLayoutController,
    ): DisposableHandle {
        return view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.stackBounds.collect { bounds ->
                        controller.setRoundedClippingBounds(
                            bounds.left.roundToInt(),
                            bounds.top.roundToInt(),
                            bounds.right.roundToInt(),
                            bounds.bottom.roundToInt(),
                            SCRIM_CORNER_RADIUS.dpToPx(context),
                            0,
                        )
                    }
                }

                launch {
                    viewModel.contentTop.collect {
                        controller.updateTopPadding(it, controller.isAddOrRemoveAnimationPending)
                    }
                }

                launch {
                    viewModel.expandFraction.collect { expandFraction ->
                        ambientState.expansionFraction = expandFraction
                        controller.expandedHeight = expandFraction * controller.view.height
                        controller.setMaxAlphaForExpansion(
                            ((expandFraction - 0.5f) / 0.5f).coerceAtLeast(0f)
                        )
                        if (expandFraction == 0f || expandFraction == 1f) {
                            controller.onExpansionStopped()
                        }
                    }
                }
            }
        }
    }

    private fun Float.dpToPx(context: Context): Int {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                this,
                context.resources.displayMetrics
            )
            .roundToInt()
    }
}
