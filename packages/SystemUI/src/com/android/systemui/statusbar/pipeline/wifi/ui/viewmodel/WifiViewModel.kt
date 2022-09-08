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
 */

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import android.graphics.Color
import androidx.annotation.DrawableRes
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_FULL_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_INTERNET_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_NETWORK
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Models the UI state for the status bar wifi icon.
 */
class WifiViewModel @Inject constructor(
    statusBarPipelineFlags: StatusBarPipelineFlags,
    private val constants: WifiConstants,
    private val logger: ConnectivityPipelineLogger,
    private val interactor: WifiInteractor,
) {
    /**
     * The drawable resource ID to use for the wifi icon. Null if we shouldn't display any icon.
     */
    @DrawableRes
    private val iconResId: Flow<Int?> = interactor.wifiNetwork.map {
        when (it) {
            is WifiNetworkModel.CarrierMerged -> null
            is WifiNetworkModel.Inactive -> WIFI_NO_NETWORK
            is WifiNetworkModel.Active ->
                when {
                    it.level == null -> null
                    it.isValidated -> WIFI_FULL_ICONS[it.level]
                    else -> WIFI_NO_INTERNET_ICONS[it.level]
                }
        }
    }

    /**
     * The wifi icon that should be displayed. Null if we shouldn't display any icon.
     */
    val wifiIcon: Flow<Icon?> = combine(
            interactor.isForceHidden,
            iconResId
        ) { isForceHidden, iconResId ->
            when {
                isForceHidden ||
                    iconResId == null ||
                    iconResId <= 0 -> null
                else -> Icon.Resource(iconResId)
            }
        }

    /**
     * True if the activity in icon should be displayed and false otherwise.
     */
    val isActivityInVisible: Flow<Boolean>
        get() =
            if (!constants.shouldShowActivityConfig) {
                flowOf(false)
            } else {
                interactor.hasActivityIn
            }
                .logOutputChange(logger, "activityInVisible")

    /** The tint that should be applied to the icon. */
    val tint: Flow<Int> = if (!statusBarPipelineFlags.useNewPipelineDebugColoring()) {
        emptyFlow()
    } else {
        flowOf(Color.CYAN)
    }
}
