/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.preview

import android.annotation.ColorInt
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.shared.clocks.shared.model.ClockPreviewConstants
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.runBlocking

/** Renders the preview of the lock screen. */
class KeyguardPreviewRenderer
@AssistedInject
constructor(
    @Application private val context: Context,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val bottomAreaViewModel: KeyguardBottomAreaViewModel,
    displayManager: DisplayManager,
    private val windowManager: WindowManager,
    private val clockController: ClockEventController,
    private val clockRegistry: ClockRegistry,
    private val broadcastDispatcher: BroadcastDispatcher,
    @Assisted bundle: Bundle,
) {

    val hostToken: IBinder? = bundle.getBinder(KEY_HOST_TOKEN)
    private val width: Int = bundle.getInt(KEY_VIEW_WIDTH)
    private val height: Int = bundle.getInt(KEY_VIEW_HEIGHT)
    private val shouldHighlightSelectedAffordance: Boolean =
        bundle.getBoolean(
            KeyguardPreviewConstants.KEY_HIGHLIGHT_QUICK_AFFORDANCES,
            false,
        )
    private val shouldHideClock: Boolean =
        bundle.getBoolean(ClockPreviewConstants.KEY_HIDE_CLOCK, false)

    private var host: SurfaceControlViewHost

    val surfacePackage: SurfaceControlViewHost.SurfacePackage
        get() = host.surfacePackage

    private var clockView: View? = null
    private var colorOverride: Int? = null

    private val disposables = mutableSetOf<DisposableHandle>()
    private var isDestroyed = false

    init {
        bottomAreaViewModel.enablePreviewMode(
            initiallySelectedSlotId =
                bundle.getString(
                    KeyguardPreviewConstants.KEY_INITIALLY_SELECTED_SLOT_ID,
                ),
            shouldHighlightSelectedAffordance = shouldHighlightSelectedAffordance,
        )
        runBlocking(mainDispatcher) {
            host =
                SurfaceControlViewHost(
                    context,
                    displayManager.getDisplay(bundle.getInt(KEY_DISPLAY_ID)),
                    hostToken,
                    "KeyguardPreviewRenderer"
                )
            disposables.add(DisposableHandle { host.release() })
        }
    }

    fun render() {
        runBlocking(mainDispatcher) {
            val rootView = FrameLayout(context)

            setUpBottomArea(rootView)
            if (!shouldHideClock) {
                setUpClock(rootView)
            }

            rootView.measure(
                View.MeasureSpec.makeMeasureSpec(
                    windowManager.currentWindowMetrics.bounds.width(),
                    View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(
                    windowManager.currentWindowMetrics.bounds.height(),
                    View.MeasureSpec.EXACTLY
                ),
            )
            rootView.layout(0, 0, rootView.measuredWidth, rootView.measuredHeight)

            // This aspect scales the view to fit in the surface and centers it
            val scale: Float =
                (width / rootView.measuredWidth.toFloat()).coerceAtMost(
                    height / rootView.measuredHeight.toFloat()
                )

            rootView.scaleX = scale
            rootView.scaleY = scale
            rootView.pivotX = 0f
            rootView.pivotY = 0f
            rootView.translationX = (width - scale * rootView.width) / 2
            rootView.translationY = (height - scale * rootView.height) / 2

            host.setView(rootView, rootView.measuredWidth, rootView.measuredHeight)
        }
    }

    fun onSlotSelected(slotId: String) {
        bottomAreaViewModel.onPreviewSlotSelected(slotId = slotId)
    }

    fun destroy() {
        isDestroyed = true
        disposables.forEach { it.dispose() }
    }

    /** Sets the clock's color to the overridden seed color. */
    fun onColorOverridden(@ColorInt color: Int?) {
        runBlocking(mainDispatcher) {
            colorOverride = color
            clockController.clock?.run { events.onSeedColorChanged(color) }
        }
    }

    private fun setUpBottomArea(parentView: ViewGroup) {
        val bottomAreaView =
            LayoutInflater.from(context)
                .inflate(
                    R.layout.keyguard_bottom_area,
                    parentView,
                    false,
                ) as KeyguardBottomAreaView
        bottomAreaView.init(
            viewModel = bottomAreaViewModel,
        )
        parentView.addView(
            bottomAreaView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun setUpClock(parentView: ViewGroup) {
        val clockChangeListener =
            object : ClockRegistry.ClockChangeListener {
                override fun onCurrentClockChanged() {
                    onClockChanged(parentView)
                }
            }
        clockRegistry.registerClockChangeListener(clockChangeListener)
        disposables.add(
            DisposableHandle { clockRegistry.unregisterClockChangeListener(clockChangeListener) }
        )

        clockController.registerListeners(parentView)
        disposables.add(DisposableHandle { clockController.unregisterListeners() })

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    clockController.clock?.run {
                        smallClock.events.onTimeTick()
                        largeClock.events.onTimeTick()
                    }
                }
            }
        broadcastDispatcher.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
            },
        )
        disposables.add(DisposableHandle { broadcastDispatcher.unregisterReceiver(receiver) })

        onClockChanged(parentView)
    }

    private fun onClockChanged(parentView: ViewGroup) {
        val clock = clockRegistry.createCurrentClock()
        clockController.clock = clock

        colorOverride?.let { clock.events.onSeedColorChanged(it) }
        if (!shouldHideClock) {
            clock.largeClock.events.onTargetRegionChanged(
                KeyguardClockSwitch.getLargeClockRegion(parentView)
            )

            clockView?.let { parentView.removeView(it) }
            clockView =
                clock.largeClock.view.apply {
                    if (shouldHighlightSelectedAffordance) {
                        alpha = DIM_ALPHA
                    }
                    parentView.addView(this)
                    visibility = View.VISIBLE
                }
        } else {
            clockView?.visibility = View.GONE
        }
    }

    companion object {
        private const val KEY_HOST_TOKEN = "host_token"
        private const val KEY_VIEW_WIDTH = "width"
        private const val KEY_VIEW_HEIGHT = "height"
        private const val KEY_DISPLAY_ID = "display_id"

        private const val DIM_ALPHA = 0.3f
    }
}
