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
import com.android.systemui.common.ui.view.onLayoutChanged
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.stack.shared.model.ViewPosition
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationStackView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationStackAppearanceViewModel
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.launchAndDispose
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Binds the NotificationStackView. */
@SysUISingleton
class NotificationStackViewBinder
@Inject
constructor(
    dumpManager: DumpManager,
    @Main private val mainImmediateDispatcher: CoroutineDispatcher,
    private val stack: NotificationStackView,
    private val viewModel: NotificationStackAppearanceViewModel,
    private val configuration: ConfigurationState,
) : FlowDumperImpl(dumpManager) {
    private val view = stack.asView()

    private val viewPosition = MutableStateFlow(ViewPosition()).dumpValue("viewPosition")
    private val viewTopOffset = viewPosition.map { it.top }.distinctUntilChanged()

    fun bindWhileAttached(): DisposableHandle {
        return view.repeatWhenAttached(mainImmediateDispatcher) {
            repeatOnLifecycle(Lifecycle.State.CREATED) { bind() }
        }
    }

    suspend fun bind() = coroutineScope {
        launchAndDispose {
            viewPosition.value = ViewPosition(view.left, view.top)
            view.onLayoutChanged { viewPosition.value = ViewPosition(it.left, it.top) }
        }

        launch {
            viewModel.shadeScrimShape(scrimRadius, viewPosition).collect { clipping ->
                stack.setRoundedClippingBounds(
                    clipping.bounds.left.roundToInt(),
                    clipping.bounds.top.roundToInt(),
                    clipping.bounds.right.roundToInt(),
                    clipping.bounds.bottom.roundToInt(),
                    clipping.topRadius,
                    clipping.bottomRadius,
                )
            }
        }

        launch { viewModel.stackTop.minusTopOffset().collect { stack.setStackTop(it) } }
        launch { viewModel.stackBottom.minusTopOffset().collect { stack.setStackBottom(it) } }
        launch { viewModel.scrolledToTop.collect { stack.setScrolledToTop(it) } }
        launch { viewModel.headsUpTop.minusTopOffset().collect { stack.setHeadsUpTop(it) } }
        launch { viewModel.expandFraction.collect { stack.setExpandFraction(it) } }
        launch { viewModel.isScrollable.collect { stack.setScrollingEnabled(it) } }

        launchAndDispose {
            stack.setSyntheticScrollConsumer(viewModel.syntheticScrollConsumer)
            stack.setStackHeightConsumer(viewModel.stackHeightConsumer)
            stack.setHeadsUpHeightConsumer(viewModel.headsUpHeightConsumer)
            DisposableHandle {
                stack.setSyntheticScrollConsumer(null)
                stack.setStackHeightConsumer(null)
                stack.setHeadsUpHeightConsumer(null)
            }
        }
    }

    /** Combine with the topOffset flow and subtract that value from this flow's value */
    private fun Flow<Float>.minusTopOffset() =
        combine(viewTopOffset) { y, topOffset -> y - topOffset }

    /** flow of the scrim clipping radius */
    private val scrimRadius: Flow<Int>
        get() = configuration.getDimensionPixelOffset(R.dimen.notification_scrim_corner_radius)
}
