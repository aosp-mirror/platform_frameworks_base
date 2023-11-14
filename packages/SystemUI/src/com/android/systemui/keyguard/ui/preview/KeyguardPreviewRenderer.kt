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
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Display.DEFAULT_DISPLAY
import android.view.DisplayInfo
import android.view.LayoutInflater
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isInvisible
import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.Flags.keyguardBottomAreaRefactor
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.keyguard.ui.binder.KeyguardPreviewClockViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardPreviewSmartspaceViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardRootViewBinder
import com.android.systemui.keyguard.ui.binder.PreviewKeyguardBlueprintViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewSmartspaceViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludingAppDeviceEntryMessageViewModel
import com.android.systemui.monet.ColorScheme
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.shared.clocks.DefaultClockController
import com.android.systemui.shared.clocks.shared.model.ClockPreviewConstants
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking

/** Renders the preview of the lock screen. */
class KeyguardPreviewRenderer
@OptIn(ExperimentalCoroutinesApi::class)
@AssistedInject
constructor(
    @Application private val context: Context,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Main private val mainHandler: Handler,
    private val clockViewModel: KeyguardPreviewClockViewModel,
    private val smartspaceViewModel: KeyguardPreviewSmartspaceViewModel,
    private val bottomAreaViewModel: KeyguardBottomAreaViewModel,
    private val quickAffordancesCombinedViewModel: KeyguardQuickAffordancesCombinedViewModel,
    displayManager: DisplayManager,
    private val windowManager: WindowManager,
    private val configuration: ConfigurationState,
    private val clockController: ClockEventController,
    private val clockRegistry: ClockRegistry,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val lockscreenSmartspaceController: LockscreenSmartspaceController,
    private val udfpsOverlayInteractor: UdfpsOverlayInteractor,
    private val featureFlags: FeatureFlagsClassic,
    private val falsingManager: FalsingManager,
    private val vibratorHelper: VibratorHelper,
    private val indicationController: KeyguardIndicationController,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    @Assisted bundle: Bundle,
    private val keyguardBlueprintViewModel: KeyguardBlueprintViewModel,
    private val occludingAppDeviceEntryMessageViewModel: OccludingAppDeviceEntryMessageViewModel,
    private val chipbarCoordinator: ChipbarCoordinator,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val shadeInteractor: ShadeInteractor,
) {

    val hostToken: IBinder? = bundle.getBinder(KEY_HOST_TOKEN)
    private val width: Int = bundle.getInt(KEY_VIEW_WIDTH)
    private val height: Int = bundle.getInt(KEY_VIEW_HEIGHT)
    private val shouldHighlightSelectedAffordance: Boolean =
        bundle.getBoolean(
            KeyguardPreviewConstants.KEY_HIGHLIGHT_QUICK_AFFORDANCES,
            false,
        )

    private val displayId = bundle.getInt(KEY_DISPLAY_ID, DEFAULT_DISPLAY)
    private val display: Display? = displayManager.getDisplay(displayId)
    /**
     * Returns a key that should make the KeyguardPreviewRenderer unique and if two of them have the
     * same key they will be treated as the same KeyguardPreviewRenderer. Primary this is used to
     * prevent memory leaks by allowing removal of the old KeyguardPreviewRenderer.
     */
    val id = Pair(hostToken, displayId)

    /** [shouldHideClock] here means that we never create and bind the clock views */
    private val shouldHideClock: Boolean =
        bundle.getBoolean(ClockPreviewConstants.KEY_HIDE_CLOCK, false)
    private val wallpaperColors: WallpaperColors? = bundle.getParcelable(KEY_COLORS)

    private var host: SurfaceControlViewHost

    val surfacePackage: SurfaceControlViewHost.SurfacePackage
        get() = checkNotNull(host.surfacePackage)

    private lateinit var largeClockHostView: FrameLayout
    private lateinit var smallClockHostView: FrameLayout
    private var smartSpaceView: View? = null

    private val disposables = mutableSetOf<DisposableHandle>()
    private var isDestroyed = false

    private val shortcutsBindings = mutableSetOf<KeyguardQuickAffordanceViewBinder.Binding>()

    init {
        if (keyguardBottomAreaRefactor()) {
            keyguardRootViewModel.enablePreviewMode()
            quickAffordancesCombinedViewModel.enablePreviewMode(
                initiallySelectedSlotId =
                    bundle.getString(
                        KeyguardPreviewConstants.KEY_INITIALLY_SELECTED_SLOT_ID,
                    )
                        ?: KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                shouldHighlightSelectedAffordance = shouldHighlightSelectedAffordance,
            )
        } else {
            bottomAreaViewModel.enablePreviewMode(
                initiallySelectedSlotId =
                    bundle.getString(
                        KeyguardPreviewConstants.KEY_INITIALLY_SELECTED_SLOT_ID,
                    ),
                shouldHighlightSelectedAffordance = shouldHighlightSelectedAffordance,
            )
        }
        runBlocking(mainDispatcher) {
            host =
                SurfaceControlViewHost(
                    context,
                    displayManager.getDisplay(DEFAULT_DISPLAY),
                    hostToken,
                    "KeyguardPreviewRenderer"
                )
            disposables.add(DisposableHandle { host.release() })
        }
    }

    fun render() {
        mainHandler.post {
            val previewContext =
                display?.let {
                    ContextThemeWrapper(context.createDisplayContext(it), context.getTheme())
                }
                    ?: context

            val rootView = FrameLayout(previewContext)

            setupKeyguardRootView(previewContext, rootView)

            if (!keyguardBottomAreaRefactor()) {
                setUpBottomArea(rootView)
            }

            var displayInfo: DisplayInfo? = null
            display?.let {
                displayInfo = DisplayInfo()
                it.getDisplayInfo(displayInfo)
            }
            rootView.measure(
                View.MeasureSpec.makeMeasureSpec(
                    displayInfo?.logicalWidth ?: windowManager.currentWindowMetrics.bounds.width(),
                    View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(
                    displayInfo?.logicalHeight
                        ?: windowManager.currentWindowMetrics.bounds.height(),
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
        if (keyguardBottomAreaRefactor()) {
            quickAffordancesCombinedViewModel.onPreviewSlotSelected(slotId = slotId)
        } else {
            bottomAreaViewModel.onPreviewSlotSelected(slotId = slotId)
        }
    }

    fun destroy() {
        isDestroyed = true
        lockscreenSmartspaceController.disconnect()
        disposables.forEach { it.dispose() }
        if (keyguardBottomAreaRefactor()) {
            shortcutsBindings.forEach { it.destroy() }
        }
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
    private fun setUpSmartspace(previewContext: Context, parentView: ViewGroup) {
        if (
            !lockscreenSmartspaceController.isEnabled() ||
                !lockscreenSmartspaceController.isDateWeatherDecoupled()
        ) {
            return
        }

        smartSpaceView = lockscreenSmartspaceController.buildAndConnectDateView(parentView)

        val topPadding: Int =
            KeyguardPreviewSmartspaceViewModel.getLargeClockSmartspaceTopPadding(
                previewContext.resources,
            )
        val startPadding: Int =
            previewContext.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start)
        val endPadding: Int =
            previewContext.resources.getDimensionPixelSize(R.dimen.below_clock_padding_end)

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

    @Deprecated("Deprecated as part of b/278057014")
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun setupKeyguardRootView(previewContext: Context, rootView: FrameLayout) {
        val keyguardRootView = KeyguardRootView(previewContext, null)
        disposables.add(
            KeyguardRootViewBinder.bind(
                keyguardRootView,
                keyguardRootViewModel,
                configuration,
                featureFlags,
                occludingAppDeviceEntryMessageViewModel,
                chipbarCoordinator,
                screenOffAnimationController,
                shadeInteractor,
                null, // clock provider only needed for burn in
                null, // jank monitor not required for preview mode
                null, // device entry haptics not required for preview mode
                null, // device entry haptics not required for preview mode
            )
        )
        rootView.addView(
            keyguardRootView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        disposables.add(
            PreviewKeyguardBlueprintViewBinder.bind(keyguardRootView, keyguardBlueprintViewModel) {
                if (keyguardBottomAreaRefactor()) {
                    setupShortcuts(keyguardRootView)
                }
                setUpUdfps(previewContext, rootView)

                if (!shouldHideClock) {
                    setUpClock(previewContext, rootView)
                    KeyguardPreviewClockViewBinder.bind(
                        largeClockHostView,
                        smallClockHostView,
                        clockViewModel,
                    )
                }

                setUpSmartspace(previewContext, rootView)
                smartSpaceView?.let {
                    KeyguardPreviewSmartspaceViewBinder.bind(it, smartspaceViewModel)
                }
            }
        )
    }

    private fun setupShortcuts(keyguardRootView: ConstraintLayout) {
        keyguardRootView.findViewById<LaunchableImageView?>(R.id.start_button)?.let {
            shortcutsBindings.add(
                KeyguardQuickAffordanceViewBinder.bind(
                    it,
                    quickAffordancesCombinedViewModel.startButton,
                    keyguardRootViewModel.alpha,
                    falsingManager,
                    vibratorHelper,
                ) {
                    indicationController.showTransientIndication(it)
                }
            )
        }

        keyguardRootView.findViewById<LaunchableImageView?>(R.id.end_button)?.let {
            shortcutsBindings.add(
                KeyguardQuickAffordanceViewBinder.bind(
                    it,
                    quickAffordancesCombinedViewModel.endButton,
                    keyguardRootViewModel.alpha,
                    falsingManager,
                    vibratorHelper,
                ) {
                    indicationController.showTransientIndication(it)
                }
            )
        }
    }

    private fun setUpUdfps(previewContext: Context, parentView: ViewGroup) {
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
            LayoutInflater.from(previewContext)
                .inflate(
                    R.layout.udfps_keyguard_preview,
                    parentView,
                    false,
                ) as View
        parentView.addView(finger, fingerprintLayoutParams)
    }

    private fun setUpClock(previewContext: Context, parentView: ViewGroup) {
        largeClockHostView =
            if (KeyguardShadeMigrationNssl.isEnabled) {
                parentView.requireViewById<FrameLayout>(R.id.lockscreen_clock_view_large)
            } else {
                val hostView = FrameLayout(previewContext)
                hostView.layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                parentView.addView(hostView)
                hostView
            }
        largeClockHostView.isInvisible = true

        smallClockHostView =
            if (KeyguardShadeMigrationNssl.isEnabled) {
                parentView.requireViewById<FrameLayout>(R.id.lockscreen_clock_view)
            } else {
                val resources = parentView.resources
                val hostView = FrameLayout(previewContext)
                val layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        resources.getDimensionPixelSize(
                            com.android.systemui.customization.R.dimen.small_clock_height
                        )
                    )
                layoutParams.topMargin =
                    KeyguardPreviewSmartspaceViewModel.getStatusBarHeight(resources) +
                        resources.getDimensionPixelSize(
                            com.android.systemui.customization.R.dimen.small_clock_padding_top
                        )
                hostView.layoutParams = layoutParams

                hostView.setPaddingRelative(
                    resources.getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.clock_padding_start
                    ),
                    0,
                    0,
                    0
                )
                hostView.clipChildren = false
                parentView.addView(hostView)
                hostView
            }
        smallClockHostView.isInvisible = true

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

    private fun onClockChanged() {
        val clock = clockRegistry.createCurrentClock()
        clockController.clock = clock

        if (clockRegistry.seedColor == null) {
            // Seed color null means users do override any color on the clock. The default color
            // will need to use wallpaper's extracted color and consider if the wallpaper's color
            // is dark or a light.
            // TODO(b/277832214) we can potentially simplify this code by checking for
            // wallpaperColors being null in the if clause above and removing the many ?.
            val wallpaperColorScheme = wallpaperColors?.let { ColorScheme(it, darkTheme = false) }
            val lightClockColor = wallpaperColorScheme?.accent1?.s100
            val darkClockColor = wallpaperColorScheme?.accent2?.s600

            // Note that when [wallpaperColors] is null, isWallpaperDark is true.
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
