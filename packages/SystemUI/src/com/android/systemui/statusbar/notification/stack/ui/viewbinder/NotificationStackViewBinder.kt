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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationStackAppearanceViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Binds the NSSL/Controller/AmbientState to their ViewModel. */
@SysUISingleton
class NotificationStackViewBinder
@Inject
constructor(
    @Main private val mainImmediateDispatcher: CoroutineDispatcher,
    private val ambientState: AmbientState,
    private val view: NotificationStackScrollLayout,
    private val controller: NotificationStackScrollLayoutController,
    private val viewModel: NotificationStackAppearanceViewModel,
    private val configuration: ConfigurationState,
) {

    fun bindWhileAttached(): DisposableHandle {
        return view.repeatWhenAttached(mainImmediateDispatcher) {
            repeatOnLifecycle(Lifecycle.State.CREATED) { bind() }
        }
    }

    suspend fun bind() = coroutineScope {
        launch {
            combine(viewModel.stackClipping, clipRadius, ::Pair).collect { (clipping, clipRadius) ->
                val (bounds, rounding) = clipping
                val viewLeft = controller.view.left
                val viewTop = controller.view.top
                controller.setRoundedClippingBounds(
                    bounds.left.roundToInt() - viewLeft,
                    bounds.top.roundToInt() - viewTop,
                    bounds.right.roundToInt() - viewLeft,
                    bounds.bottom.roundToInt() - viewTop,
                    if (rounding.roundTop) clipRadius else 0,
                    if (rounding.roundBottom) clipRadius else 0,
                )
            }
        }

        launch {
            viewModel.contentTop.collect {
                controller.updateTopPadding(it, controller.isAddOrRemoveAnimationPending)
            }
        }

        launch {
            var wasExpanding = false
            viewModel.expandFraction.collect { expandFraction ->
                val nowExpanding = expandFraction != 0f && expandFraction != 1f
                if (nowExpanding && !wasExpanding) {
                    controller.onExpansionStarted()
                }
                ambientState.expansionFraction = expandFraction
                controller.expandedHeight = expandFraction * controller.view.height
                if (!nowExpanding && wasExpanding) {
                    controller.onExpansionStopped()
                }
                wasExpanding = nowExpanding
            }
        }

        launch { viewModel.isScrollable.collect { controller.setScrollingEnabled(it) } }
    }

    private val clipRadius: Flow<Int>
        get() = configuration.getDimensionPixelOffset(R.dimen.notification_scrim_corner_radius)
}
