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
import com.android.systemui.R
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

    // TODO(b/194178072) Handle RSSI hiding when multi carrier
    private val iconManager: StatusBarIconController.IconManager
    private val qsCarrierGroupController: QSCarrierGroupController
    private var visible = false

    var shadeExpanded = false
        set(value) {
            field = value
            updateVisibility()
        }

    var splitShadeMode = false
        set(value) {
            field = value
            updateVisibility()
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
    }

    private fun updateVisibility() {
        val shouldBeVisible = shadeExpanded && splitShadeMode
        if (visible != shouldBeVisible) {
            visible = shouldBeVisible
            statusBar.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
            updateListeners(shouldBeVisible)
        }
    }

    private fun updateListeners(visible: Boolean) {
        qsCarrierGroupController.setListening(visible)
        if (visible) {
            statusBarIconController.addIconGroup(iconManager)
        } else {
            statusBarIconController.removeIconGroup(iconManager)
        }
    }
}