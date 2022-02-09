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

package com.android.systemui.statusbar.phone

import android.app.StatusBarManager
import android.content.res.Configuration
import android.os.Trace
import android.os.Trace.TRACE_TAG_APP
import android.util.Pair
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.TextView
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.settingslib.Utils
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.qs.ChipVisibilityListener
import com.android.systemui.qs.HeaderPrivacyIconsController
import com.android.systemui.qs.carrier.QSCarrierGroup
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.statusbar.phone.LargeScreenShadeHeaderController.Companion.HEADER_TRANSITION_ID
import com.android.systemui.statusbar.phone.LargeScreenShadeHeaderController.Companion.LARGE_SCREEN_HEADER_CONSTRAINT
import com.android.systemui.statusbar.phone.LargeScreenShadeHeaderController.Companion.LARGE_SCREEN_HEADER_TRANSITION_ID
import com.android.systemui.statusbar.phone.LargeScreenShadeHeaderController.Companion.QQS_HEADER_CONSTRAINT
import com.android.systemui.statusbar.phone.LargeScreenShadeHeaderController.Companion.QS_HEADER_CONSTRAINT
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent.CentralSurfacesScope
import com.android.systemui.statusbar.phone.dagger.StatusBarViewModule.LARGE_SCREEN_BATTERY_CONTROLLER
import com.android.systemui.statusbar.phone.dagger.StatusBarViewModule.LARGE_SCREEN_SHADE_HEADER
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.VariableDateView
import com.android.systemui.statusbar.policy.VariableDateViewController
import com.android.systemui.util.ViewController
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Named

/**
 * Controller for QS header on Large Screen width (large screen + landscape).
 *
 * Additionally, this serves as the staging ground for the combined QS headers. A single
 * [MotionLayout] that changes constraints depending on the configuration and can animate the
 * expansion of the headers in small screen portrait.
 *
 * [header] will be a [MotionLayout] if [Flags.COMBINED_QS_HEADERS] is enabled. In this case, the
 * [MotionLayout] has 2 transitions:
 * * [HEADER_TRANSITION_ID]: [QQS_HEADER_CONSTRAINT] <-> [QS_HEADER_CONSTRAINT] for portrait
 *   handheld device configuration.
 * * [LARGE_SCREEN_HEADER_TRANSITION_ID]: [LARGE_SCREEN_HEADER_CONSTRAINT] (to itself) for all
 *   other configurations
 */
@CentralSurfacesScope
class LargeScreenShadeHeaderController @Inject constructor(
    @Named(LARGE_SCREEN_SHADE_HEADER) private val header: View,
    private val statusBarIconController: StatusBarIconController,
    private val privacyIconsController: HeaderPrivacyIconsController,
    private val insetsProvider: StatusBarContentInsetsProvider,
    private val configurationController: ConfigurationController,
    private val variableDateViewControllerFactory: VariableDateViewController.Factory,
    @Named(LARGE_SCREEN_BATTERY_CONTROLLER)
    private val batteryMeterViewController: BatteryMeterViewController,
    private val dumpManager: DumpManager,
    private val featureFlags: FeatureFlags,
    private val qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder
) : ViewController<View>(header), Dumpable {

    companion object {
        /** IDs for transitions and constraints for the [MotionLayout]. These are only used when
         * [Flags.COMBINED_QS_HEADERS] is enabled.
         */
        private val HEADER_TRANSITION_ID = R.id.header_transition
        private val LARGE_SCREEN_HEADER_TRANSITION_ID = R.id.large_screen_header_transition
        private val QQS_HEADER_CONSTRAINT = R.id.qqs_header_constraint
        private val QS_HEADER_CONSTRAINT = R.id.qs_header_constraint
        private val LARGE_SCREEN_HEADER_CONSTRAINT = R.id.large_screen_header_constraint

        private fun Int.stateToString() = when (this) {
            QQS_HEADER_CONSTRAINT -> "QQS Header"
            QS_HEADER_CONSTRAINT -> "QS Header"
            LARGE_SCREEN_HEADER_CONSTRAINT -> "Large Screen Header"
            else -> "Unknown state"
        }
    }

    init {
        loadConstraints()
    }

    private val combinedHeaders = featureFlags.isEnabled(Flags.COMBINED_QS_HEADERS)

    private lateinit var iconManager: StatusBarIconController.TintedIconManager
    private lateinit var carrierIconSlots: List<String>
    private lateinit var qsCarrierGroupController: QSCarrierGroupController

    private val batteryIcon: BatteryMeterView = header.findViewById(R.id.batteryRemainingIcon)
    private val clock: TextView = header.findViewById(R.id.clock)
    private val date: TextView = header.findViewById(R.id.date)
    private val iconContainer: StatusIconContainer = header.findViewById(R.id.statusIcons)
    private val qsCarrierGroup: QSCarrierGroup = header.findViewById(R.id.carrier_group)

    private var cutoutLeft = 0
    private var cutoutRight = 0
    private var roundedCorners = 0
    private var lastInsets: WindowInsets? = null

    private var qsDisabled = false
    private var visible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateListeners()
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

    /**
     * Expansion fraction of the QQS/QS shade. This is not the expansion between QQS <-> QS.
     */
    var shadeExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                header.alpha = ShadeInterpolation.getContentAlpha(value)
                field = value
            }
        }

    /**
     * Expansion fraction of the QQS <-> QS animation.
     */
    var qsExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                field = value
                updateVisibility()
                updatePosition()
            }
        }

    /**
     * Current scroll of QS.
     */
    var qsScrollY = 0
        set(value) {
            if (field != value) {
                field = value
                updateScrollY()
            }
        }

    private val insetListener = View.OnApplyWindowInsetsListener { view, insets ->
        updateConstraintsForInsets(view as MotionLayout, insets)
        lastInsets = WindowInsets(insets)

        view.onApplyWindowInsets(insets)
    }

    private val chipVisibilityListener: ChipVisibilityListener = object : ChipVisibilityListener {
        override fun onChipVisibilityRefreshed(visible: Boolean) {
            if (header is MotionLayout) {
                // If the privacy chip is visible, we hide the status icons and battery remaining
                // icon, only in QQS.
                val constraintAlpha = if (visible) 0f else 1f
                val state = header.getConstraintSet(QQS_HEADER_CONSTRAINT).apply {
                    setAlpha(R.id.statusIcons, constraintAlpha)
                    setAlpha(R.id.batteryRemainingIcon, constraintAlpha)
                }
                header.updateState(QQS_HEADER_CONSTRAINT, state)
            }
        }
    }

    private val configurationControllerListener =
        object : ConfigurationController.ConfigurationListener {
        override fun onConfigChanged(newConfig: Configuration?) {
            if (header !is MotionLayout) {
                val left = header.resources.getDimensionPixelSize(
                    R.dimen.large_screen_shade_header_left_padding
                )
                header.setPadding(
                    left,
                    header.paddingTop,
                    header.paddingRight,
                    header.paddingBottom
                )
            }
        }

        override fun onDensityOrFontScaleChanged() {
            clock.setTextAppearance(R.style.TextAppearance_QS_Status)
            date.setTextAppearance(R.style.TextAppearance_QS_Status)
            qsCarrierGroup.updateTextAppearance(R.style.TextAppearance_QS_Status_Carriers)
            if (header is MotionLayout) {
                loadConstraints()
                lastInsets?.let { updateConstraintsForInsets(header, it) }
            }
            updateResources()
        }
    }

    override fun onInit() {
        if (header is MotionLayout) {
            variableDateViewControllerFactory.create(date as VariableDateView).init()
        }
        batteryMeterViewController.init()

        // battery settings same as in QS icons
        batteryMeterViewController.ignoreTunerUpdates()
        batteryIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)

        iconManager = StatusBarIconController.TintedIconManager(iconContainer, featureFlags)
        iconManager.setTint(
            Utils.getColorAttrDefaultColor(header.context, android.R.attr.textColorPrimary)
        )

        carrierIconSlots = if (featureFlags.isEnabled(Flags.COMBINED_STATUS_BAR_SIGNAL_ICONS)) {
            listOf(
                header.context.getString(com.android.internal.R.string.status_bar_no_calling),
                header.context.getString(com.android.internal.R.string.status_bar_call_strength)
            )
        } else {
            listOf(header.context.getString(com.android.internal.R.string.status_bar_mobile))
        }
        qsCarrierGroupController = qsCarrierGroupControllerBuilder
            .setQSCarrierGroup(qsCarrierGroup)
            .build()
    }

    override fun onViewAttached() {
        privacyIconsController.chipVisibilityListener = chipVisibilityListener
        if (header is MotionLayout) {
            header.setOnApplyWindowInsetsListener(insetListener)
        }

        dumpManager.registerDumpable(this)
        configurationController.addCallback(configurationControllerListener)

        updateVisibility()
        updateTransition()
    }

    override fun onViewDetached() {
        privacyIconsController.chipVisibilityListener = null
        dumpManager.unregisterDumpable(this::class.java.simpleName)
        configurationController.removeCallback(configurationControllerListener)
    }

    fun disable(state1: Int, state2: Int, animate: Boolean) {
        val disabled = state2 and StatusBarManager.DISABLE2_QUICK_SETTINGS != 0
        if (disabled == qsDisabled) return
        qsDisabled = disabled
        updateVisibility()
    }

    private fun loadConstraints() {
        if (header is MotionLayout) {
            // Use resources.getXml instead of passing the resource id due to bug b/205018300
            header.getConstraintSet(QQS_HEADER_CONSTRAINT)
                .load(context, resources.getXml(R.xml.qqs_header))
            val qsConstraints = if (featureFlags.isEnabled(Flags.NEW_HEADER)) {
                R.xml.qs_header_new
            } else {
                R.xml.qs_header
            }
            header.getConstraintSet(QS_HEADER_CONSTRAINT)
                .load(context, resources.getXml(qsConstraints))
            header.getConstraintSet(LARGE_SCREEN_HEADER_CONSTRAINT)
                .load(context, resources.getXml(R.xml.large_screen_shade_header))
        }
    }

    private fun updateConstraintsForInsets(view: MotionLayout, insets: WindowInsets) {
        val cutout = insets.displayCutout

        val sbInsets: Pair<Int, Int> = insetsProvider.getStatusBarContentInsetsForCurrentRotation()
        cutoutLeft = sbInsets.first
        cutoutRight = sbInsets.second
        val hasCornerCutout: Boolean = insetsProvider.currentRotationHasCornerCutout()
        val collapsedConstraint = view.getConstraintSet(QQS_HEADER_CONSTRAINT)
        updateQQSPaddings()
        // Set these guides as the left/right limits for content that lives in the top row, using
        // cutoutLeft and cutoutRight
        view.getConstraintSet(QQS_HEADER_CONSTRAINT).updateGuides()
        view.getConstraintSet(QS_HEADER_CONSTRAINT).updateGuides()

        if (cutout != null) {
            val topCutout = cutout.boundingRectTop
            if (topCutout.isEmpty || hasCornerCutout) {
                updateConstraintsForNoCutout(view)
            } else {
                val rtl = view.isLayoutRtl
                val centerStart = if (!rtl) R.id.center_left else R.id.center_right
                val centerEnd = if (!rtl) R.id.center_right else R.id.center_left
                val offsetFromEdge =
                    (view.width - view.paddingLeft - view.paddingStart) / 2 - topCutout.width() / 2
                collapsedConstraint.apply {
                    // Use guidelines to block the center cutout area.
                    setGuidelineBegin(centerStart, offsetFromEdge)
                    setGuidelineEnd(centerEnd, offsetFromEdge)
                    connect(R.id.date, ConstraintSet.END, centerStart, ConstraintSet.START)
                    connect(
                        R.id.statusIcons,
                        ConstraintSet.START,
                        centerEnd,
                        ConstraintSet.END
                    )
                    connect(
                        R.id.privacy_container,
                        ConstraintSet.START,
                        centerEnd,
                        ConstraintSet.END
                    )
                    constrainWidth(R.id.statusIcons, 0)
                }
            }
        } else {
            updateConstraintsForNoCutout(view)
        }

        view.updateState(QQS_HEADER_CONSTRAINT, collapsedConstraint)
    }

    private fun ConstraintSet.updateGuides() {
        setGuidelineBegin(R.id.begin_guide, Math.max(cutoutLeft - header.paddingLeft, 0))
        setGuidelineEnd(R.id.end_guide, Math.max(cutoutRight - header.paddingRight, 0))
    }

    /**
     * If there's no center cutout, either due to no cutouts at all or just corner cutouts, update
     * constraints so elements are not constrained in the center.
     */
    private fun updateConstraintsForNoCutout(view: MotionLayout) {
        val collapsedConstraint = view.getConstraintSet(QQS_HEADER_CONSTRAINT)
        collapsedConstraint.apply {
            connect(R.id.date, ConstraintSet.END, R.id.barrier, ConstraintSet.START)
            createBarrier(
                R.id.barrier,
                ConstraintSet.START,
                0,
                R.id.statusIcons,
                R.id.privacy_container
            )
            connect(R.id.statusIcons, ConstraintSet.START, R.id.date, ConstraintSet.END)
            connect(R.id.privacy_container, ConstraintSet.START, R.id.date, ConstraintSet.END)
            constrainWidth(R.id.statusIcons, WRAP_CONTENT)
        }
    }

    private fun updateScrollY() {
        if (!largeScreenActive && combinedHeaders) {
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
        if (largeScreenActive || combinedHeaders) {
            privacyIconsController.onParentVisible()
        } else {
            privacyIconsController.onParentInvisible()
        }
        updateVisibility()
        updateTransition()
    }

    /**
     * If not using [combinedHeaders] this should only be visible on large screen. Else, it should
     * be visible any time the QQS/QS shade is open.
     */
    private fun updateVisibility() {
        val visibility = if (!largeScreenActive && !combinedHeaders || qsDisabled) {
            View.GONE
        } else if (qsVisible) {
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
        if (!combinedHeaders) {
            return
        }
        header as MotionLayout
        if (largeScreenActive) {
            header.setTransition(LARGE_SCREEN_HEADER_TRANSITION_ID)
            header.getConstraintSet(LARGE_SCREEN_HEADER_CONSTRAINT).applyTo(header)
        } else {
            header.setTransition(HEADER_TRANSITION_ID)
            header.transitionToStart()
            updatePosition()
            updateScrollY()
        }
    }

    private fun updatePosition() {
        if (header is MotionLayout && !largeScreenActive && visible) {
            Trace.instantForTrack(
                TRACE_TAG_APP,
                "LargeScreenHeaderController - updatePosition",
                "position: $qsExpandedFraction"
            )
            header.progress = qsExpandedFraction
        }
    }

    private fun updateListeners() {
        qsCarrierGroupController.setListening(visible)
        if (visible) {
            updateSingleCarrier(qsCarrierGroupController.isSingleCarrier)
            qsCarrierGroupController.setOnSingleCarrierChangedListener { updateSingleCarrier(it) }
            statusBarIconController.addIconGroup(iconManager)
        } else {
            qsCarrierGroupController.setOnSingleCarrierChangedListener(null)
            statusBarIconController.removeIconGroup(iconManager)
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
    }

    private fun updateQQSPaddings() {
        if (header is MotionLayout) {
            val clockPaddingStart = resources
                .getDimensionPixelSize(R.dimen.status_bar_left_clock_starting_padding)
            val clockPaddingEnd = resources
                .getDimensionPixelSize(R.dimen.status_bar_left_clock_end_padding)
            clock.setPaddingRelative(
                clockPaddingStart,
                clock.paddingTop,
                clockPaddingEnd,
                clock.paddingBottom
            )
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("visible: $visible")
        pw.println("shadeExpanded: $qsVisible")
        pw.println("shadeExpandedFraction: $shadeExpandedFraction")
        pw.println("active: $largeScreenActive")
        pw.println("qsExpandedFraction: $qsExpandedFraction")
        pw.println("qsScrollY: $qsScrollY")
        if (combinedHeaders) {
            header as MotionLayout
            pw.println("currentState: ${header.currentState.stateToString()}")
        }
    }
}
