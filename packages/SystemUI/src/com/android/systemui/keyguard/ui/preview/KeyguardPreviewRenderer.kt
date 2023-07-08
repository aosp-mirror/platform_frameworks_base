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

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.view.LayoutInflater
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.isInvisible
import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.R
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ui.binder.KeyguardPreviewClockViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardPreviewSmartspaceViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewSmartspaceViewModel
import com.android.systemui.monet.ColorScheme
import com.android.systemui.plugins.ClockController
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.shared.clocks.DefaultClockController
import com.android.systemui.shared.clocks.shared.model.ClockPreviewConstants
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
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
    @Main private val mainHandler: Handler,
    private val clockViewModel: KeyguardPreviewClockViewModel,
    private val smartspaceViewModel: KeyguardPreviewSmartspaceViewModel,
    private val bottomAreaViewModel: KeyguardBottomAreaViewModel,
    displayManager: DisplayManager,
    private val windowManager: WindowManager,
    private val clockController: ClockEventController,
    private val clockRegistry: ClockRegistry,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val lockscreenSmartspaceController: LockscreenSmartspaceController,
    private val udfpsOverlayInteractor: UdfpsOverlayInteractor,
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
    /** [shouldHideClock] here means that we never create and bind the clock views */
    private val shouldHideClock: Boolean =
        bundle.getBoolean(ClockPreviewConstants.KEY_HIDE_CLOCK, false)
    private val wallpaperColors: WallpaperColors? = bundle.getParcelable(KEY_COLORS)

    private var host: SurfaceControlViewHost

    val surfacePackage: SurfaceControlViewHost.SurfacePackage
        get() = host.surfacePackage

    private lateinit var largeClockHostView: FrameLayout
    private lateinit var smallClockHostView: FrameLayout
    private var smartSpaceView: View? = null

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
        mainHandler.post {
            val rootView = FrameLayout(context)

            setUpBottomArea(rootView)

            setUpSmartspace(rootView)
            smartSpaceView?.let {
                KeyguardPreviewSmartspaceViewBinder.bind(it, smartspaceViewModel)
            }

            setUpUdfps(rootView)

            if (!shouldHideClock) {
                setUpClock(rootView)
                KeyguardPreviewClockViewBinder.bind(
                    largeClockHostView,
                    smallClockHostView,
                    clockViewModel,
                )
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

            if (isDestroyed) {
                return@post
            }

            host.setView(rootView, rootView.measuredWidth, rootView.measuredHeight)
        }
    }

    fun onSlotSelected(slotId: String) {
        bottomAreaViewModel.onPreviewSlotSelected(slotId = slotId)
    }

    fun destroy() {
        isDestroyed = true
        lockscreenSmartspaceController.disconnect()
        disposables.forEach { it.dispose() }
    }

    /**
     * Hides or shows smartspace
     *
     * @param hide TRUE hides smartspace, FALSE shows smartspace
     */
    fun hideSmartspace(hide: Boolean) {
        mainHandler.post { smartSpaceView?.visibility = if (hide) View.INVISIBLE else View.VISIBLE }
    }

    /**
     * This sets up and shows a non-interactive smart space
     *
     * The top padding is as follows: Status bar height + clock top margin + keyguard smart space
     * top offset
     *
     * The start padding is as follows: Clock padding start + Below clock padding start
     *
     * The end padding is as follows: Below clock padding end
     */
    private fun setUpSmartspace(parentView: ViewGroup) {
        if (
            !lockscreenSmartspaceController.isEnabled() ||
                !lockscreenSmartspaceController.isDateWeatherDecoupled()
        ) {
            return
        }

        smartSpaceView = lockscreenSmartspaceController.buildAndConnectDateView(parentView)

        val topPadding: Int =
            KeyguardPreviewSmartspaceViewModel.getLargeClockSmartspaceTopPadding(
                context.resources,
            )

        val startPadding: Int =
            with(context.resources) {
                getDimensionPixelSize(R.dimen.clock_padding_start) +
                    getDimensionPixelSize(R.dimen.below_clock_padding_start)
            }

        val endPadding: Int =
            context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_end)

        smartSpaceView?.let {
            it.setPaddingRelative(startPadding, topPadding, endPadding, 0)
            it.isClickable = false
            it.isInvisible = true
            parentView.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        smartSpaceView?.alpha = if (shouldHighlightSelectedAffordance) DIM_ALPHA else 1.0f
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

    private fun setUpUdfps(parentView: ViewGroup) {
        val sensorBounds = udfpsOverlayInteractor.udfpsOverlayParams.value.sensorBounds

        // If sensorBounds are default rect, then there is no UDFPS
        if (sensorBounds == Rect()) {
            return
        }

        // Place the UDFPS view in the proper sensor location
        val fingerprintLayoutParams =
            FrameLayout.LayoutParams(sensorBounds.width(), sensorBounds.height())
        fingerprintLayoutParams.setMarginsRelative(
            sensorBounds.left,
            sensorBounds.top,
            sensorBounds.right,
            sensorBounds.bottom
        )
        val finger =
            LayoutInflater.from(context)
                .inflate(
                    R.layout.udfps_keyguard_preview,
                    parentView,
                    false,
                ) as View
        parentView.addView(finger, fingerprintLayoutParams)
    }

    private fun setUpClock(parentView: ViewGroup) {
        largeClockHostView = createLargeClockHostView()
        largeClockHostView.isInvisible = true
        parentView.addView(largeClockHostView)

        smallClockHostView = createSmallClockHostView(parentView.resources)
        smallClockHostView.isInvisible = true
        parentView.addView(smallClockHostView)

        // TODO (b/283465254): Move the listeners to KeyguardClockRepository
        val clockChangeListener =
            object : ClockRegistry.ClockChangeListener {
                override fun onCurrentClockChanged() {
                    onClockChanged()
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

        val layoutChangeListener =
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (clockController.clock !is DefaultClockController) {
                    clockController.clock
                        ?.largeClock
                        ?.events
                        ?.onTargetRegionChanged(KeyguardClockSwitch.getLargeClockRegion(parentView))
                    clockController.clock
                        ?.smallClock
                        ?.events
                        ?.onTargetRegionChanged(KeyguardClockSwitch.getSmallClockRegion(parentView))
                }
            }
        parentView.addOnLayoutChangeListener(layoutChangeListener)
        disposables.add(
            DisposableHandle { parentView.removeOnLayoutChangeListener(layoutChangeListener) }
        )

        onClockChanged()
    }

    private fun createLargeClockHostView(): FrameLayout {
        val hostView = FrameLayout(context)
        hostView.layoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        return hostView
    }

    private fun createSmallClockHostView(resources: Resources): FrameLayout {
        val hostView = FrameLayout(context)
        val layoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                resources.getDimensionPixelSize(R.dimen.small_clock_height)
            )
        layoutParams.topMargin =
            KeyguardPreviewSmartspaceViewModel.getStatusBarHeight(resources) +
                resources.getDimensionPixelSize(R.dimen.small_clock_padding_top)
        hostView.layoutParams = layoutParams

        hostView.setPaddingRelative(
            resources.getDimensionPixelSize(R.dimen.clock_padding_start),
            0,
            0,
            0
        )
        hostView.clipChildren = false
        return hostView
    }

    private fun onClockChanged() {
        val clock = clockRegistry.createCurrentClock()
        clockController.clock = clock

        if (clockRegistry.seedColor == null) {
            // Seed color null means users do override any color on the clock. The default color
            // will need to use wallpaper's extracted color and consider if the wallpaper's color
            // is dark or a light.
            // TODO(b/277832214) we can potentially simplify this code by checking for
            // wallpaperColors being null in the if clause above and removing the many ?.
            val wallpaperColorScheme =
                wallpaperColors?.let { ColorScheme(it, /* darkTheme= */ false) }
            val lightClockColor = wallpaperColorScheme?.accent1?.s100
            val darkClockColor = wallpaperColorScheme?.accent2?.s600
            /** Note that when [wallpaperColors] is null, isWallpaperDark is true. */
            val isWallpaperDark: Boolean =
                (wallpaperColors?.colorHints?.and(WallpaperColors.HINT_SUPPORTS_DARK_TEXT)) == 0
            clock.events.onSeedColorChanged(
                if (isWallpaperDark) lightClockColor else darkClockColor
            )
        }

        updateLargeClock(clock)
        updateSmallClock(clock)
    }

    private fun updateLargeClock(clock: ClockController) {
        clock.largeClock.events.onTargetRegionChanged(
            KeyguardClockSwitch.getLargeClockRegion(largeClockHostView)
        )
        if (shouldHighlightSelectedAffordance) {
            clock.largeClock.view.alpha = DIM_ALPHA
        }
        largeClockHostView.removeAllViews()
        largeClockHostView.addView(clock.largeClock.view)
    }

    private fun updateSmallClock(clock: ClockController) {
        clock.smallClock.events.onTargetRegionChanged(
            KeyguardClockSwitch.getSmallClockRegion(smallClockHostView)
        )
        if (shouldHighlightSelectedAffordance) {
            clock.smallClock.view.alpha = DIM_ALPHA
        }
        smallClockHostView.removeAllViews()
        smallClockHostView.addView(clock.smallClock.view)
    }

    companion object {
        private const val KEY_HOST_TOKEN = "host_token"
        private const val KEY_VIEW_WIDTH = "width"
        private const val KEY_VIEW_HEIGHT = "height"
        private const val KEY_DISPLAY_ID = "display_id"
        private const val KEY_COLORS = "wallpaper_colors"

        private const val DIM_ALPHA = 0.3f
    }
}
