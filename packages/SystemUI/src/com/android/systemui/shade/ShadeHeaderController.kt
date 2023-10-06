/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.IdRes
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Trace
import android.os.Trace.TRACE_TAG_APP
import android.provider.AlarmClock
import android.util.Pair
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.view.doOnLayout
import com.android.app.animation.Interpolators
import com.android.settingslib.Utils
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.ChipVisibilityListener
import com.android.systemui.qs.HeaderPrivacyIconsController
import com.android.systemui.shade.ShadeHeaderController.Companion.HEADER_TRANSITION_ID
import com.android.systemui.shade.ShadeHeaderController.Companion.LARGE_SCREEN_HEADER_CONSTRAINT
import com.android.systemui.shade.ShadeHeaderController.Companion.LARGE_SCREEN_HEADER_TRANSITION_ID
import com.android.systemui.shade.ShadeHeaderController.Companion.QQS_HEADER_CONSTRAINT
import com.android.systemui.shade.ShadeHeaderController.Companion.QS_HEADER_CONSTRAINT
import com.android.systemui.shade.ShadeModule.Companion.SHADE_HEADER
import com.android.systemui.shade.carrier.ShadeCarrierGroup
import com.android.systemui.shade.carrier.ShadeCarrierGroupController
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.VariableDateView
import com.android.systemui.statusbar.policy.VariableDateViewController
import com.android.systemui.util.ViewController
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Named

/**
 * Controller for QS header.
 *
 * [header] is a [MotionLayout] that has two transitions:
 * * [HEADER_TRANSITION_ID]: [QQS_HEADER_CONSTRAINT] <-> [QS_HEADER_CONSTRAINT] for portrait
 *   handheld device configuration.
 * * [LARGE_SCREEN_HEADER_TRANSITION_ID]: [LARGE_SCREEN_HEADER_CONSTRAINT] for all other
 *   configurations
 */
@SysUISingleton
class ShadeHeaderController
@Inject
constructor(
    @Named(SHADE_HEADER) private val header: MotionLayout,
    private val statusBarIconController: StatusBarIconController,
    private val tintedIconManagerFactory: StatusBarIconController.TintedIconManager.Factory,
    private val privacyIconsController: HeaderPrivacyIconsController,
    private val insetsProvider: StatusBarContentInsetsProvider,
    private val configurationController: ConfigurationController,
    private val variableDateViewControllerFactory: VariableDateViewController.Factory,
    @Named(SHADE_HEADER) private val batteryMeterViewController: BatteryMeterViewController,
    private val dumpManager: DumpManager,
    private val shadeCarrierGroupControllerBuilder: ShadeCarrierGroupController.Builder,
    private val combinedShadeHeadersConstraintManager: CombinedShadeHeadersConstraintManager,
    private val demoModeController: DemoModeController,
    private val qsBatteryModeController: QsBatteryModeController,
    private val nextAlarmController: NextAlarmController,
    private val activityStarter: ActivityStarter,
) : ViewController<View>(header), Dumpable {

    companion object {
        /** IDs for transitions and constraints for the [MotionLayout]. */
        @VisibleForTesting internal val HEADER_TRANSITION_ID = R.id.header_transition
        @VisibleForTesting
        internal val LARGE_SCREEN_HEADER_TRANSITION_ID = R.id.large_screen_header_transition
        @VisibleForTesting internal val QQS_HEADER_CONSTRAINT = R.id.qqs_header_constraint
        @VisibleForTesting internal val QS_HEADER_CONSTRAINT = R.id.qs_header_constraint
        @VisibleForTesting
        internal val LARGE_SCREEN_HEADER_CONSTRAINT = R.id.large_screen_header_constraint

        @VisibleForTesting internal val DEFAULT_CLOCK_INTENT = Intent(AlarmClock.ACTION_SHOW_ALARMS)

        private fun Int.stateToString() =
            when (this) {
                QQS_HEADER_CONSTRAINT -> "QQS Header"
                QS_HEADER_CONSTRAINT -> "QS Header"
                LARGE_SCREEN_HEADER_CONSTRAINT -> "Large Screen Header"
                else -> "Unknown state $this"
            }
    }

    private lateinit var iconManager: StatusBarIconController.TintedIconManager
    private lateinit var carrierIconSlots: List<String>
    private lateinit var mShadeCarrierGroupController: ShadeCarrierGroupController

    private val batteryIcon: BatteryMeterView = header.requireViewById(R.id.batteryRemainingIcon)
    private val clock: Clock = header.requireViewById(R.id.clock)
    private val date: TextView = header.requireViewById(R.id.date)
    private val iconContainer: StatusIconContainer = header.requireViewById(R.id.statusIcons)
    private val mShadeCarrierGroup: ShadeCarrierGroup = header.requireViewById(R.id.carrier_group)

    private var roundedCorners = 0
    private var cutout: DisplayCutout? = null
    private var lastInsets: WindowInsets? = null
    private var nextAlarmIntent: PendingIntent? = null

    private var qsDisabled = false
    private var visible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateListeners()
        }

    private var customizing = false
        set(value) {
            if (field != value) {
                field = value
                updateVisibility()
            }
        }

    /**
     * Whether the QQS/QS part of the shade is visible. This is particularly important in
     * Lockscreen, as the shade is visible but QS is not.
     */
    var qsVisible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            onShadeExpandedChanged()
        }

    /**
     * Whether we are in a configuration with large screen width. In this case, the header is a
     * single line.
     */
    var largeScreenActive = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            onHeaderStateChanged()
        }

    /** Expansion fraction of the QQS/QS shade. This is not the expansion between QQS <-> QS. */
    var shadeExpandedFraction = -1f
        set(value) {
            if (qsVisible && field != value) {
                header.alpha = ShadeInterpolation.getContentAlpha(value)
                field = value
            }
        }

    /** Expansion fraction of the QQS <-> QS animation. */
    var qsExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                field = value
                updatePosition()
            }
        }

    /** Current scroll of QS. */
    var qsScrollY = 0
        set(value) {
            if (field != value) {
                field = value
                updateScrollY()
            }
        }

    private val insetListener =
        View.OnApplyWindowInsetsListener { view, insets ->
            updateConstraintsForInsets(view as MotionLayout, insets)
            lastInsets = WindowInsets(insets)

            view.onApplyWindowInsets(insets)
        }

    private val demoModeReceiver =
        object : DemoMode {
            override fun demoCommands() = listOf(DemoMode.COMMAND_CLOCK)
            override fun dispatchDemoCommand(command: String, args: Bundle) =
                clock.dispatchDemoCommand(command, args)

            override fun onDemoModeStarted() = clock.onDemoModeStarted()
            override fun onDemoModeFinished() = clock.onDemoModeFinished()
        }

    private val chipVisibilityListener: ChipVisibilityListener =
        object : ChipVisibilityListener {
            override fun onChipVisibilityRefreshed(visible: Boolean) {
                // If the privacy chip is visible, we hide the status icons and battery remaining
                // icon, only in QQS.
                val update =
                    combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(visible)
                header.updateAllConstraints(update)
            }
        }

    private val configurationControllerListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                val left =
                    header.resources.getDimensionPixelSize(
                        R.dimen.large_screen_shade_header_left_padding
                    )
                header.setPadding(
                    left,
                    header.paddingTop,
                    header.paddingRight,
                    header.paddingBottom
                )
            }

            override fun onDensityOrFontScaleChanged() {
                clock.setTextAppearance(R.style.TextAppearance_QS_Status)
                date.setTextAppearance(R.style.TextAppearance_QS_Status)
                mShadeCarrierGroup.updateTextAppearance(R.style.TextAppearance_QS_Status_Carriers)
                loadConstraints()
                header.minHeight =
                    resources.getDimensionPixelSize(R.dimen.large_screen_shade_header_min_height)
                lastInsets?.let { updateConstraintsForInsets(header, it) }
                updateResources()
                updateCarrierGroupPadding()
            }
        }

    private val nextAlarmCallback =
        NextAlarmController.NextAlarmChangeCallback { nextAlarm ->
            nextAlarmIntent = nextAlarm?.showIntent
        }

    override fun onInit() {
        variableDateViewControllerFactory.create(date as VariableDateView).init()
        batteryMeterViewController.init()

        // battery settings same as in QS icons
        batteryMeterViewController.ignoreTunerUpdates()

        iconManager = tintedIconManagerFactory.create(iconContainer, StatusBarLocation.QS)
        iconManager.setTint(
            Utils.getColorAttrDefaultColor(header.context, android.R.attr.textColorPrimary)
        )

        carrierIconSlots =
            listOf(header.context.getString(com.android.internal.R.string.status_bar_mobile))
        mShadeCarrierGroupController =
            shadeCarrierGroupControllerBuilder.setShadeCarrierGroup(mShadeCarrierGroup).build()

        privacyIconsController.onParentVisible()
    }

    override fun onViewAttached() {
        privacyIconsController.chipVisibilityListener = chipVisibilityListener
        updateVisibility()
        updateTransition()
        updateCarrierGroupPadding()

        header.setOnApplyWindowInsetsListener(insetListener)

        clock.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val newPivot = if (v.isLayoutRtl) v.width.toFloat() else 0f
            v.pivotX = newPivot
            v.pivotY = v.height.toFloat() / 2
        }
        clock.setOnClickListener { launchClockActivity() }

        dumpManager.registerDumpable(this)
        configurationController.addCallback(configurationControllerListener)
        demoModeController.addCallback(demoModeReceiver)
        statusBarIconController.addIconGroup(iconManager)
        nextAlarmController.addCallback(nextAlarmCallback)
    }

    override fun onViewDetached() {
        clock.setOnClickListener(null)
        privacyIconsController.chipVisibilityListener = null
        dumpManager.unregisterDumpable(this::class.java.simpleName)
        configurationController.removeCallback(configurationControllerListener)
        demoModeController.removeCallback(demoModeReceiver)
        statusBarIconController.removeIconGroup(iconManager)
        nextAlarmController.removeCallback(nextAlarmCallback)
    }

    fun disable(state1: Int, state2: Int, animate: Boolean) {
        val disabled = state2 and StatusBarManager.DISABLE2_QUICK_SETTINGS != 0
        if (disabled == qsDisabled) return
        qsDisabled = disabled
        updateVisibility()
    }

    fun startCustomizingAnimation(show: Boolean, duration: Long) {
        header
            .animate()
            .setDuration(duration)
            .alpha(if (show) 0f else 1f)
            .setInterpolator(if (show) Interpolators.ALPHA_OUT else Interpolators.ALPHA_IN)
            .setListener(CustomizerAnimationListener(show))
            .start()
    }

    @VisibleForTesting
    internal fun launchClockActivity() {
        if (nextAlarmIntent != null) {
            activityStarter.postStartActivityDismissingKeyguard(nextAlarmIntent)
        } else {
            activityStarter.postStartActivityDismissingKeyguard(DEFAULT_CLOCK_INTENT, 0 /*delay */)
        }
    }

    private fun loadConstraints() {
        // Use resources.getXml instead of passing the resource id due to bug b/205018300
        header
            .getConstraintSet(QQS_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.qqs_header))
        header
            .getConstraintSet(QS_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.qs_header))
        header
            .getConstraintSet(LARGE_SCREEN_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.large_screen_shade_header))
    }

    private fun updateCarrierGroupPadding() {
        clock.doOnLayout {
            val maxClockWidth =
                (clock.width * resources.getFloat(R.dimen.qqs_expand_clock_scale)).toInt()
            mShadeCarrierGroup.setPaddingRelative(maxClockWidth, 0, 0, 0)
        }
    }

    private fun updateConstraintsForInsets(view: MotionLayout, insets: WindowInsets) {
        val cutout = insets.displayCutout.also { this.cutout = it }

        val sbInsets: Pair<Int, Int> = insetsProvider.getStatusBarContentInsetsForCurrentRotation()
        val cutoutLeft = sbInsets.first
        val cutoutRight = sbInsets.second
        val hasCornerCutout: Boolean = insetsProvider.currentRotationHasCornerCutout()
        updateQQSPaddings()
        // Set these guides as the left/right limits for content that lives in the top row, using
        // cutoutLeft and cutoutRight
        var changes =
            combinedShadeHeadersConstraintManager.edgesGuidelinesConstraints(
                if (view.isLayoutRtl) cutoutRight else cutoutLeft,
                header.paddingStart,
                if (view.isLayoutRtl) cutoutLeft else cutoutRight,
                header.paddingEnd
            )

        if (cutout != null) {
            val topCutout = cutout.boundingRectTop
            if (topCutout.isEmpty || hasCornerCutout) {
                changes += combinedShadeHeadersConstraintManager.emptyCutoutConstraints()
            } else {
                changes +=
                    combinedShadeHeadersConstraintManager.centerCutoutConstraints(
                        view.isLayoutRtl,
                        (view.width - view.paddingLeft - view.paddingRight - topCutout.width()) / 2
                    )
            }
        } else {
            changes += combinedShadeHeadersConstraintManager.emptyCutoutConstraints()
        }

        view.updateAllConstraints(changes)
        updateBatteryMode()
    }

    private fun updateBatteryMode() {
        qsBatteryModeController.getBatteryMode(cutout, qsExpandedFraction)?.let {
            batteryIcon.setPercentShowMode(it)
        }
    }

    private fun updateScrollY() {
        if (!largeScreenActive) {
            header.scrollY = qsScrollY
        }
    }

    private fun onShadeExpandedChanged() {
        if (qsVisible) {
            privacyIconsController.startListening()
        } else {
            privacyIconsController.stopListening()
        }
        updateVisibility()
        updatePosition()
    }

    private fun onHeaderStateChanged() {
        updateTransition()
    }

    /**
     * If not using [combinedHeaders] this should only be visible on large screen. Else, it should
     * be visible any time the QQS/QS shade is open.
     */
    private fun updateVisibility() {
        val visibility =
            if (qsDisabled) {
                View.GONE
            } else if (qsVisible && !customizing) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        if (header.visibility != visibility) {
            header.visibility = visibility
            visible = visibility == View.VISIBLE
        }
    }

    private fun updateTransition() {
        if (largeScreenActive) {
            logInstantEvent("Large screen constraints set")
            header.setTransition(LARGE_SCREEN_HEADER_TRANSITION_ID)
        } else {
            logInstantEvent("Small screen constraints set")
            header.setTransition(HEADER_TRANSITION_ID)
        }
        header.jumpToState(header.startState)
        updatePosition()
        updateScrollY()
    }

    private fun updatePosition() {
        if (!largeScreenActive && visible) {
            logInstantEvent("updatePosition: $qsExpandedFraction")
            header.progress = qsExpandedFraction
            updateBatteryMode()
        }
    }

    private fun logInstantEvent(message: String) {
        Trace.instantForTrack(TRACE_TAG_APP, "LargeScreenHeaderController", message)
    }

    private fun updateListeners() {
        mShadeCarrierGroupController.setListening(visible)
        if (visible) {
            updateSingleCarrier(mShadeCarrierGroupController.isSingleCarrier)
            mShadeCarrierGroupController.setOnSingleCarrierChangedListener {
                updateSingleCarrier(it)
            }
        } else {
            mShadeCarrierGroupController.setOnSingleCarrierChangedListener(null)
        }
    }

    private fun updateSingleCarrier(singleCarrier: Boolean) {
        if (singleCarrier) {
            iconContainer.removeIgnoredSlots(carrierIconSlots)
        } else {
            iconContainer.addIgnoredSlots(carrierIconSlots)
        }
    }

    private fun updateResources() {
        roundedCorners = resources.getDimensionPixelSize(R.dimen.rounded_corner_content_padding)
        val padding = resources.getDimensionPixelSize(R.dimen.qs_panel_padding)
        header.setPadding(padding, header.paddingTop, padding, header.paddingBottom)
        updateQQSPaddings()
        qsBatteryModeController.updateResources()
    }

    private fun updateQQSPaddings() {
        val clockPaddingStart =
            resources.getDimensionPixelSize(R.dimen.status_bar_left_clock_starting_padding)
        val clockPaddingEnd =
            resources.getDimensionPixelSize(R.dimen.status_bar_left_clock_end_padding)
        clock.setPaddingRelative(
            clockPaddingStart,
            clock.paddingTop,
            clockPaddingEnd,
            clock.paddingBottom
        )
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("visible: $visible")
        pw.println("shadeExpanded: $qsVisible")
        pw.println("shadeExpandedFraction: $shadeExpandedFraction")
        pw.println("active: $largeScreenActive")
        pw.println("qsExpandedFraction: $qsExpandedFraction")
        pw.println("qsScrollY: $qsScrollY")
        pw.println("currentState: ${header.currentState.stateToString()}")
    }

    private fun MotionLayout.updateConstraints(@IdRes state: Int, update: ConstraintChange) {
        val constraints = getConstraintSet(state)
        constraints.update()
        updateState(state, constraints)
    }

    /**
     * Updates the [ConstraintSet] for the case of combined headers.
     *
     * Only non-`null` changes are applied to reduce the number of rebuilding in the [MotionLayout].
     */
    private fun MotionLayout.updateAllConstraints(updates: ConstraintsChanges) {
        if (updates.qqsConstraintsChanges != null) {
            updateConstraints(QQS_HEADER_CONSTRAINT, updates.qqsConstraintsChanges)
        }
        if (updates.qsConstraintsChanges != null) {
            updateConstraints(QS_HEADER_CONSTRAINT, updates.qsConstraintsChanges)
        }
        if (updates.largeScreenConstraintsChanges != null) {
            updateConstraints(LARGE_SCREEN_HEADER_CONSTRAINT, updates.largeScreenConstraintsChanges)
        }
    }

    @VisibleForTesting internal fun simulateViewDetached() = this.onViewDetached()

    inner class CustomizerAnimationListener(
        private val enteringCustomizing: Boolean,
    ) : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            header.animate().setListener(null)
            if (enteringCustomizing) {
                customizing = true
            }
        }

        override fun onAnimationStart(animation: Animator) {
            super.onAnimationStart(animation)
            if (!enteringCustomizing) {
                customizing = false
            }
        }
    }
}
