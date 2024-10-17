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

import android.util.Log
import com.android.app.tracing.coroutines.flow.collectTraced
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.view.onLayoutChanged
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationScrollViewModel
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.launchAndDispose
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter

/** Binds the [NotificationScrollView]. */
@SysUISingleton
class NotificationScrollViewBinder
@Inject
constructor(
    dumpManager: DumpManager,
    @Main private val mainImmediateDispatcher: CoroutineDispatcher,
    private val view: NotificationScrollView,
    private val viewModelFactory: NotificationScrollViewModel.Factory,
    private val configuration: ConfigurationState,
) : FlowDumperImpl(dumpManager) {

    private val viewLeftOffset = MutableStateFlow(0).dumpValue("viewLeftOffset")

    private fun updateViewPosition() {
        val trueView = view.asView()
        if (trueView.top != 0) {
            Log.w("NSSL", "Expected $trueView to have top==0")
        }
        viewLeftOffset.value = trueView.left
    }

    fun bindWhileAttached(): DisposableHandle {
        return view.asView().repeatWhenAttached(mainImmediateDispatcher) { bind() }
    }

    suspend fun bind(): Nothing =
        view.asView().viewModel(
            traceName = "NotificationScrollViewBinder",
            minWindowLifecycleState = WindowLifecycleState.ATTACHED,
            factory = viewModelFactory::create,
        ) { viewModel ->
            launchAndDispose {
                updateViewPosition()
                view.asView().onLayoutChanged { updateViewPosition() }
            }

            launch {
                viewModel
                    .shadeScrimShape(cornerRadius = scrimRadius, viewLeftOffset = viewLeftOffset)
                    .collectTraced { view.setScrimClippingShape(it) }
            }

            launch { viewModel.maxAlpha.collectTraced { view.setMaxAlpha(it) } }
            launch { viewModel.scrolledToTop.collectTraced { view.setScrolledToTop(it) } }
            launch {
                viewModel.expandFraction.collectTraced { view.setExpandFraction(it.coerceIn(0f, 1f)) }
            }
            launch { viewModel.qsExpandFraction.collectTraced { view.setQsExpandFraction(it) } }
            launch {
                viewModel.isShowingStackOnLockscreen.collectTraced {
                    view.setShowingStackOnLockscreen(it)
                }
            }
            launch {
                viewModel.alphaForLockscreenFadeIn.collectTraced { view.setAlphaForLockscreenFadeIn(it) }
            }
            launch { viewModel.isScrollable.collectTraced { view.setScrollingEnabled(it) } }
            launch { viewModel.isDozing.collectTraced { isDozing -> view.setDozing(isDozing) } }
            launch {
                viewModel.isPulsing.collectTraced { isPulsing ->
                    view.setPulsing(isPulsing, viewModel.shouldAnimatePulse.value)
                }
            }
            launch {
                viewModel.shouldResetStackTop
                    .filter { it }
                    .collectTraced { view.setStackTop(-(view.getHeadsUpInset().toFloat())) }
            }
            launch {
                viewModel.shouldCloseGuts.filter { it }.collectTraced { view.closeGutsOnSceneTouch() }
            }
            launch { viewModel.suppressHeightUpdates.collectTraced { view.suppressHeightUpdates(it) } }

            launchAndDispose {
                view.setSyntheticScrollConsumer(viewModel.syntheticScrollConsumer)
                view.setCurrentGestureOverscrollConsumer(viewModel.currentGestureOverscrollConsumer)
                view.setCurrentGestureInGutsConsumer(viewModel.currentGestureInGutsConsumer)
                view.setRemoteInputRowBottomBoundConsumer(
                    viewModel.remoteInputRowBottomBoundConsumer
                )
                DisposableHandle {
                    view.setSyntheticScrollConsumer(null)
                    view.setCurrentGestureOverscrollConsumer(null)
                    view.setCurrentGestureInGutsConsumer(null)
                    view.setRemoteInputRowBottomBoundConsumer(null)
                }
            }
        }

    /** flow of the scrim clipping radius */
    private val scrimRadius: Flow<Int>
        get() = configuration.getDimensionPixelOffset(R.dimen.notification_scrim_corner_radius)
}
