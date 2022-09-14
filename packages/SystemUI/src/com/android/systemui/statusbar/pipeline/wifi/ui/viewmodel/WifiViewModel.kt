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

import android.content.Context
import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_NO_CONNECTION
import com.android.systemui.R
import com.android.systemui.common.shared.model.ContentDescription
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
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiActivityModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Models the UI state for the status bar wifi icon.
 */
class WifiViewModel @Inject constructor(
    constants: WifiConstants,
    private val context: Context,
    logger: ConnectivityPipelineLogger,
    interactor: WifiInteractor,
    statusBarPipelineFlags: StatusBarPipelineFlags,
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

    /** The content description for the wifi icon. */
    private val contentDescription: Flow<ContentDescription?> = interactor.wifiNetwork.map {
        when (it) {
            is WifiNetworkModel.CarrierMerged -> null
            is WifiNetworkModel.Inactive ->
                ContentDescription.Loaded(
                    "${context.getString(WIFI_NO_CONNECTION)},${context.getString(NO_INTERNET)}"
                )
            is WifiNetworkModel.Active ->
                when (it.level) {
                    null -> null
                    else -> {
                        val levelDesc = context.getString(WIFI_CONNECTION_STRENGTH[it.level])
                        when {
                            it.isValidated -> ContentDescription.Loaded(levelDesc)
                            else -> ContentDescription.Loaded(
                                "$levelDesc,${context.getString(NO_INTERNET)}"
                            )
                        }
                    }
                }
        }
    }

    /**
     * The wifi icon that should be displayed. Null if we shouldn't display any icon.
     */
    val wifiIcon: Flow<Icon?> = combine(
            interactor.isForceHidden,
            iconResId,
            contentDescription,
        ) { isForceHidden, iconResId, contentDescription ->
            when {
                isForceHidden ||
                    iconResId == null ||
                    iconResId <= 0 -> null
                else -> Icon.Resource(iconResId, contentDescription)
            }
        }

    /** The wifi activity status. Null if we shouldn't display the activity status. */
    private val activity: Flow<WifiActivityModel?> =
        if (!constants.shouldShowActivityConfig) {
            flowOf(null)
        } else {
            combine(interactor.activity, interactor.ssid) { activity, ssid ->
                when (ssid) {
                    null -> null
                    else -> activity
                }
            }
        }
        .distinctUntilChanged()
        .logOutputChange(logger, "activity")

    /** True if the activity in view should be visible. */
    val isActivityInViewVisible: Flow<Boolean> = activity.map { it?.hasActivityIn == true }

    /** True if the activity out view should be visible. */
    val isActivityOutViewVisible: Flow<Boolean> = activity.map { it?.hasActivityOut == true }

    /** True if the activity container view should be visible. */
    val isActivityContainerVisible: Flow<Boolean> =
            combine(isActivityInViewVisible, isActivityOutViewVisible) { activityIn, activityOut ->
                activityIn || activityOut
            }

    // TODO(b/238425913): Update this class to use state flows instead. Right now, we have a ton of
    //  duplicate activity logs because the cold flows are getting duplicated for the three
    //  activityVisible flows.

    /** The tint that should be applied to the icon. */
    val tint: Flow<Int> = if (!statusBarPipelineFlags.useNewPipelineDebugColoring()) {
        emptyFlow()
    } else {
        flowOf(Color.CYAN)
    }

    companion object {
        @StringRes
        @VisibleForTesting
        internal val NO_INTERNET = R.string.data_connection_no_internet
    }
}
