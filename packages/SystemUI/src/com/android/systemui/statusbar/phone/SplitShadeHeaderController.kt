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

import android.view.View
import androidx.constraintlayout.motion.widget.MotionLayout
import com.android.systemui.R
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent.StatusBarScope
import com.android.systemui.statusbar.phone.dagger.StatusBarViewModule.SPLIT_SHADE_HEADER
import javax.inject.Inject
import javax.inject.Named

@StatusBarScope
class SplitShadeHeaderController @Inject constructor(
    @Named(SPLIT_SHADE_HEADER) private val statusBar: View,
    private val statusBarIconController: StatusBarIconController,
    qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder,
    featureFlags: FeatureFlags,
    batteryMeterViewController: BatteryMeterViewController
) {

    companion object {
        private val HEADER_TRANSITION_ID = R.id.header_transition
        private val SPLIT_HEADER_TRANSITION_ID = R.id.split_header_transition
    }

    private val combinedHeaders = featureFlags.useCombinedQSHeaders()
    // TODO(b/194178072) Handle RSSI hiding when multi carrier
    private val iconManager: StatusBarIconController.IconManager
    private val qsCarrierGroupController: QSCarrierGroupController
    private var visible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateListeners()
        }

    var shadeExpanded = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateVisibility()
            updatePosition()
        }

    var splitShadeMode = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateVisibility()
            updateConstraints()
        }

    var shadeExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                statusBar.alpha = ShadeInterpolation.getContentAlpha(value)
                field = value
            }
        }

    var qsExpandedFraction = -1f
        set(value) {
            if (visible && field != value) {
                field = value
                updateVisibility()
                updatePosition()
            }
        }

    init {
        if (statusBar is MotionLayout) {
            val context = statusBar.context
            val resources = statusBar.resources
            statusBar.getConstraintSet(R.id.qqs_header_constraint)
                    .load(context, resources.getXml(R.xml.qqs_header))
            statusBar.getConstraintSet(R.id.qs_header_constraint)
                    .load(context, resources.getXml(R.xml.qs_header))
            statusBar.getConstraintSet(R.id.split_header_constraint)
                    .load(context, resources.getXml(R.xml.split_header))
        }
    }

    init {
        batteryMeterViewController.init()
        val batteryIcon: BatteryMeterView = statusBar.findViewById(R.id.batteryRemainingIcon)

        // battery settings same as in QS icons
        batteryMeterViewController.ignoreTunerUpdates()
        batteryIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)

        val iconContainer: StatusIconContainer = statusBar.findViewById(R.id.statusIcons)
        iconManager = StatusBarIconController.IconManager(iconContainer, featureFlags)
        qsCarrierGroupController = qsCarrierGroupControllerBuilder
                .setQSCarrierGroup(statusBar.findViewById(R.id.carrier_group))
                .build()
        updateVisibility()
        updateConstraints()
    }

    private fun updateVisibility() {
        val visibility = if (!splitShadeMode && !combinedHeaders) {
            View.GONE
        } else if (shadeExpanded) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        if (statusBar.visibility != visibility) {
            statusBar.visibility = visibility
            visible = visibility == View.VISIBLE
        }
    }

    private fun updateConstraints() {
        if (!combinedHeaders) {
            return
        }
        statusBar as MotionLayout
        if (splitShadeMode) {
            statusBar.setTransition(SPLIT_HEADER_TRANSITION_ID)
        } else {
            statusBar.setTransition(HEADER_TRANSITION_ID)
            statusBar.transitionToStart()
            updatePosition()
        }
    }

    private fun updatePosition() {
        if (statusBar is MotionLayout && !splitShadeMode && visible) {
            statusBar.setProgress(qsExpandedFraction)
        }
    }

    private fun updateListeners() {
        qsCarrierGroupController.setListening(visible)
        if (visible) {
            statusBarIconController.addIconGroup(iconManager)
        } else {
            statusBarIconController.removeIconGroup(iconManager)
        }
    }
}