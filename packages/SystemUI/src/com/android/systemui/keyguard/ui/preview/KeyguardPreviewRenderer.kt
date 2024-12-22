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

import com.android.app.tracing.coroutines.createCoroutineTracingContext
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
import android.provider.Settings
import android.util.Log
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
import android.widget.TextView
import android.window.InputTransferToken
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.core.view.isInvisible
import com.android.internal.policy.SystemBarUtils
import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.communal.ui.binder.CommunalTutorialIndicatorViewBinder
import com.android.systemui.communal.ui.viewmodel.CommunalTutorialIndicatorViewModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardBottomAreaRefactor
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.binder.KeyguardPreviewClockViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardPreviewSmartspaceViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardRootViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewSmartspaceViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludingAppDeviceEntryMessageViewModel
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.Style
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.shared.clocks.DefaultClockController
import com.android.systemui.shared.clocks.shared.model.ClockPreviewConstants
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.util.kotlin.DisposableHandles
import com.android.systemui.util.settings.SecureSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Renders the preview of the lock screen. */
class KeyguardPreviewRenderer
@OptIn(ExperimentalCoroutinesApi::class)
@AssistedInject
constructor(
    @Application private val context: Context,
    @Application applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Main private val mainHandler: Handler,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
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
    private val indicationController: KeyguardIndicationController,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val keyguardBlueprintViewModel: KeyguardBlueprintViewModel,
    @Assisted bundle: Bundle,
    private val occludingAppDeviceEntryMessageViewModel: OccludingAppDeviceEntryMessageViewModel,
    private val chipbarCoordinator: ChipbarCoordinator,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val shadeInteractor: ShadeInteractor,
    private val secureSettings: SecureSettings,
    private val communalTutorialViewModel: CommunalTutorialIndicatorViewModel,
    private val defaultShortcutsSection: DefaultShortcutsSection,
    private val keyguardClockInteractor: KeyguardClockInteractor,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    private val keyguardQuickAffordanceViewBinder: KeyguardQuickAffordanceViewBinder,
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

    private val disposables = DisposableHandles()
    private var isDestroyed = false

    private val shortcutsBindings = mutableSetOf<KeyguardQuickAffordanceViewBinder.Binding>()

    private val coroutineScope: CoroutineScope
    private var themeStyle: Style? = null

    init {
        coroutineScope = CoroutineScope(applicationScope.coroutineContext + Job() + createCoroutineTracingContext("KeyguardPreviewRenderer"))
        disposables += DisposableHandle { coroutineScope.cancel() }
        clockController.setFallbackWeatherData(WeatherData.getPlaceholderWeatherData())

        if (KeyguardBottomAreaRefactor.isEnabled) {
            quickAffordancesCombinedViewModel.enablePreviewMode(
                initiallySelectedSlotId =
                    bundle.getString(
                        KeyguardPreviewConstants.KEY_INITIALLY_SELECTED_SLOT_ID,
                    ) ?: KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
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
        if (MigrateClocksToBlueprint.isEnabled) {
            clockViewModel.shouldHighlightSelectedAffordance = shouldHighlightSelectedAffordance
        }
        runBlocking(mainDispatcher) {
            host =
                SurfaceControlViewHost(
                    context,
                    displayManager.getDisplay(DEFAULT_DISPLAY),
                    if (hostToken == null) null else InputTransferToken(hostToken),
                    "KeyguardPreviewRenderer"
                )
            disposables += DisposableHandle { host.release() }
        }
    }

    fun render() {
        mainHandler.post {
            val previewContext =
                display?.let {
                    ContextThemeWrapper(context.createDisplayContext(it), context.getTheme())
                } ?: context

            val rootView = FrameLayout(previewContext)

            setupKeyguardRootView(previewContext, rootView)

            if (!KeyguardBottomAreaRefactor.isEnabled) {
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

    fun onStartCustomizingQuickAffordances(
        initiallySelectedSlotId: String?,
    ) {
        quickAffordancesCombinedViewModel.enablePreviewMode(
            initiallySelectedSlotId = initiallySelectedSlotId,
            shouldHighlightSelectedAffordance = true,
        )
    }

    fun onSlotSelected(slotId: String) {
        if (KeyguardBottomAreaRefactor.isEnabled) {
            quickAffordancesCombinedViewModel.onPreviewSlotSelected(slotId = slotId)
        } else {
            bottomAreaViewModel.onPreviewSlotSelected(slotId = slotId)
        }
    }

    fun onPreviewQuickAffordanceSelected(slotId: String, quickAffordanceId: String) {
        quickAffordancesCombinedViewModel.onPreviewQuickAffordanceSelected(
            slotId,
            quickAffordanceId,
        )
    }

    fun onDefaultPreview() {
        quickAffordancesCombinedViewModel.onClearPreviewQuickAffordances()
        quickAffordancesCombinedViewModel.enablePreviewMode(
            initiallySelectedSlotId = null,
            shouldHighlightSelectedAffordance = false,
        )
    }

    fun destroy() {
        isDestroyed = true
        lockscreenSmartspaceController.disconnect()
        disposables.dispose()
        if (KeyguardBottomAreaRefactor.isEnabled) {
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
            !lockscreenSmartspaceController.isEnabled ||
                !lockscreenSmartspaceController.isDateWeatherDecoupled
        ) {
            return
        }

        if (smartSpaceView != null) {
            parentView.removeView(smartSpaceView)
        }

        smartSpaceView = lockscreenSmartspaceController.buildAndConnectDateView(parentView)

        val topPadding: Int =
            smartspaceViewModel.getLargeClockSmartspaceTopPadding(
                previewInSplitShade(),
                previewContext,
            )
        val startPadding: Int = smartspaceViewModel.getSmartspaceStartPadding(previewContext)
        val endPadding: Int = smartspaceViewModel.getSmartspaceEndPadding(previewContext)

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
        if (!KeyguardBottomAreaRefactor.isEnabled) {
            disposables +=
                KeyguardRootViewBinder.bind(
                    keyguardRootView,
                    keyguardRootViewModel,
                    keyguardBlueprintViewModel,
                    configuration,
                    occludingAppDeviceEntryMessageViewModel,
                    chipbarCoordinator,
                    screenOffAnimationController,
                    shadeInteractor,
                    keyguardClockInteractor,
                    keyguardClockViewModel,
                    null, // jank monitor not required for preview mode
                    null, // device entry haptics not required preview mode
                    null, // device entry haptics not required for preview mode
                    null, // falsing manager not required for preview mode
                    null, // keyguard view mediator is not required for preview mode
                    null, // primary bouncer interactor is not required for preview mode
                    mainDispatcher,
                    null,
                )
        }
        rootView.addView(
            keyguardRootView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        setUpUdfps(
            previewContext,
            if (MigrateClocksToBlueprint.isEnabled) keyguardRootView else rootView
        )

        if (KeyguardBottomAreaRefactor.isEnabled) {
            setupShortcuts(keyguardRootView)
        }

        if (!shouldHideClock) {
            setUpClock(previewContext, rootView)
            if (MigrateClocksToBlueprint.isEnabled) {
                KeyguardPreviewClockViewBinder.bind(
                    previewContext,
                    keyguardRootView,
                    clockViewModel,
                    clockRegistry,
                    ::updateClockAppearance,
                )
            } else {
                KeyguardPreviewClockViewBinder.bind(
                    largeClockHostView,
                    smallClockHostView,
                    clockViewModel,
                )
            }
        }

        setUpSmartspace(previewContext, rootView)

        smartSpaceView?.let {
            KeyguardPreviewSmartspaceViewBinder.bind(
                previewContext,
                it,
                previewInSplitShade(),
                smartspaceViewModel
            )
        }
        setupCommunalTutorialIndicator(keyguardRootView)
    }

    private fun setupShortcuts(keyguardRootView: ConstraintLayout) {
        // Add shortcuts
        val cs = ConstraintSet()
        cs.clone(keyguardRootView)
        defaultShortcutsSection.addViews(keyguardRootView)
        defaultShortcutsSection.applyConstraints(cs)
        cs.applyTo(keyguardRootView)

        keyguardRootView.findViewById<LaunchableImageView?>(R.id.start_button)?.let { imageView ->
            shortcutsBindings.add(
                keyguardQuickAffordanceViewBinder.bind(
                    view = imageView,
                    viewModel = quickAffordancesCombinedViewModel.startButton,
                    alpha = flowOf(1f),
                ) { message ->
                    indicationController.showTransientIndication(message)
                }
            )
        }

        keyguardRootView.findViewById<LaunchableImageView?>(R.id.end_button)?.let { imageView ->
            shortcutsBindings.add(
                keyguardQuickAffordanceViewBinder.bind(
                    view = imageView,
                    viewModel = quickAffordancesCombinedViewModel.endButton,
                    alpha = flowOf(1f),
                ) { message ->
                    indicationController.showTransientIndication(message)
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

        val finger =
            LayoutInflater.from(previewContext)
                .inflate(
                    R.layout.udfps_keyguard_preview,
                    parentView,
                    false,
                ) as View

        // Place the UDFPS view in the proper sensor location
        if (MigrateClocksToBlueprint.isEnabled) {
            finger.id = R.id.lock_icon_view
            parentView.addView(finger)
            val cs = ConstraintSet()
            cs.clone(parentView as ConstraintLayout)
            cs.apply {
                constrainWidth(R.id.lock_icon_view, sensorBounds.width())
                constrainHeight(R.id.lock_icon_view, sensorBounds.height())
                connect(R.id.lock_icon_view, TOP, PARENT_ID, TOP, sensorBounds.top)
                connect(R.id.lock_icon_view, START, PARENT_ID, START, sensorBounds.left)
            }
            cs.applyTo(parentView)
        } else {
            val fingerprintLayoutParams =
                FrameLayout.LayoutParams(sensorBounds.width(), sensorBounds.height())
            fingerprintLayoutParams.setMarginsRelative(
                sensorBounds.left,
                sensorBounds.top,
                sensorBounds.right,
                sensorBounds.bottom
            )
            parentView.addView(finger, fingerprintLayoutParams)
        }
    }

    private fun setUpClock(previewContext: Context, parentView: ViewGroup) {
        val resources = parentView.resources
        if (!MigrateClocksToBlueprint.isEnabled) {
            largeClockHostView = FrameLayout(previewContext)
            largeClockHostView.layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            largeClockHostView.isInvisible = true
            parentView.addView(largeClockHostView)

            smallClockHostView = FrameLayout(previewContext)
            val layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.small_clock_height
                    )
                )
            layoutParams.topMargin =
                SystemBarUtils.getStatusBarHeight(previewContext) +
                    resources.getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.small_clock_padding_top
                    )
            smallClockHostView.layoutParams = layoutParams
            smallClockHostView.setPaddingRelative(
                /* start = */ resources.getDimensionPixelSize(
                    com.android.systemui.customization.R.dimen.clock_padding_start
                ),
                /* top = */ 0,
                /* end = */ 0,
                /* bottom = */ 0
            )
            smallClockHostView.clipChildren = false
            parentView.addView(smallClockHostView)
            smallClockHostView.isInvisible = true
        }

        // TODO (b/283465254): Move the listeners to KeyguardClockRepository
        if (!MigrateClocksToBlueprint.isEnabled) {
            val clockChangeListener =
                object : ClockRegistry.ClockChangeListener {
                    override fun onCurrentClockChanged() {
                        onClockChanged()
                    }
                }
            clockRegistry.registerClockChangeListener(clockChangeListener)
            disposables += DisposableHandle {
                clockRegistry.unregisterClockChangeListener(clockChangeListener)
            }

            clockController.registerListeners(parentView)
            disposables += DisposableHandle { clockController.unregisterListeners() }
        }

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
        disposables += DisposableHandle { broadcastDispatcher.unregisterReceiver(receiver) }

        if (!MigrateClocksToBlueprint.isEnabled) {
            val layoutChangeListener =
                View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    if (clockController.clock !is DefaultClockController) {
                        clockController.clock
                            ?.largeClock
                            ?.events
                            ?.onTargetRegionChanged(
                                KeyguardClockSwitch.getLargeClockRegion(parentView)
                            )
                        clockController.clock
                            ?.smallClock
                            ?.events
                            ?.onTargetRegionChanged(
                                KeyguardClockSwitch.getSmallClockRegion(parentView)
                            )
                    }
                }
            parentView.addOnLayoutChangeListener(layoutChangeListener)
            disposables += DisposableHandle {
                parentView.removeOnLayoutChangeListener(layoutChangeListener)
            }
        }

        onClockChanged()
    }

    private suspend fun updateClockAppearance(clock: ClockController) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            clockController.clock = clock
        }
        val colors = wallpaperColors
        if (clockRegistry.seedColor == null && colors != null) {
            // Seed color null means users do not override any color on the clock. The default
            // color will need to use wallpaper's extracted color and consider if the
            // wallpaper's color is dark or light.
            val style = themeStyle ?: fetchThemeStyleFromSetting().also { themeStyle = it }
            val wallpaperColorScheme = ColorScheme(colors, false, style)
            val lightClockColor = wallpaperColorScheme.accent1.s100
            val darkClockColor = wallpaperColorScheme.accent2.s600

            // Note that when [wallpaperColors] is null, isWallpaperDark is true.
            val isWallpaperDark: Boolean =
                (colors.colorHints.and(WallpaperColors.HINT_SUPPORTS_DARK_TEXT)) == 0
            clock.events.onSeedColorChanged(
                if (isWallpaperDark) lightClockColor else darkClockColor
            )
        }
        // In clock preview, we should have a seed color for clock
        // before setting clock to clockEventController to avoid updateColor with seedColor == null
        if (MigrateClocksToBlueprint.isEnabled) {
            clockController.clock = clock
        }
    }

    private fun onClockChanged() {
        if (MigrateClocksToBlueprint.isEnabled) {
            return
        }
        coroutineScope.launch {
            val clock = clockRegistry.createCurrentClock()
            clockController.clock = clock
            updateClockAppearance(clock)
            updateLargeClock(clock)
            updateSmallClock(clock)
        }
    }

    private fun setupCommunalTutorialIndicator(keyguardRootView: ConstraintLayout) {
        keyguardRootView.findViewById<TextView>(R.id.communal_tutorial_indicator)?.let {
            indicatorView ->
            CommunalTutorialIndicatorViewBinder.bind(
                indicatorView,
                communalTutorialViewModel,
                isPreviewMode = true,
            )
        }
    }

    private suspend fun fetchThemeStyleFromSetting(): Style {
        val overlayPackageJson =
            withContext(backgroundDispatcher) {
                secureSettings.getString(
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                )
            }
        return if (!overlayPackageJson.isNullOrEmpty()) {
            try {
                val jsonObject = JSONObject(overlayPackageJson)
                Style.valueOf(jsonObject.getString(OVERLAY_CATEGORY_THEME_STYLE))
            } catch (e: (JSONException)) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e)
                Style.TONAL_SPOT
            } catch (e: IllegalArgumentException) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e)
                Style.TONAL_SPOT
            }
        } else {
            Style.TONAL_SPOT
        }
    }

    private fun updateLargeClock(clock: ClockController) {
        if (MigrateClocksToBlueprint.isEnabled) {
            return
        }
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
        if (MigrateClocksToBlueprint.isEnabled) {
            return
        }
        clock.smallClock.events.onTargetRegionChanged(
            KeyguardClockSwitch.getSmallClockRegion(smallClockHostView)
        )
        if (shouldHighlightSelectedAffordance) {
            clock.smallClock.view.alpha = DIM_ALPHA
        }
        smallClockHostView.removeAllViews()
        smallClockHostView.addView(clock.smallClock.view)
    }

    /*
     * When multi_crop_preview_ui_flag is on, we can preview portrait in split shadow direction
     * or vice versa. So we need to decide preview direction by width and height
     */
    private fun previewInSplitShade(): Boolean {
        return width > height
    }

    companion object {
        private const val TAG = "KeyguardPreviewRenderer"
        private const val OVERLAY_CATEGORY_THEME_STYLE = "android.theme.customization.theme_style"
        private const val KEY_HOST_TOKEN = "host_token"
        private const val KEY_VIEW_WIDTH = "width"
        private const val KEY_VIEW_HEIGHT = "height"
        private const val KEY_DISPLAY_ID = "display_id"
        private const val KEY_COLORS = "wallpaper_colors"

        const val DIM_ALPHA = 0.3f
    }
}
