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
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_NO_CONNECTION
import com.android.systemui.R
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_FULL_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_INTERNET_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_NETWORK
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiActivityModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Models the UI state for the status bar wifi icon.
 *
 * This class exposes three view models, one per status bar location:
 *  - [home]
 *  - [keyguard]
 *  - [qs]
 *  In order to get the UI state for the wifi icon, you must use one of those view models (whichever
 *  is correct for your location).
 *
 * Internally, this class maintains the current state of the wifi icon and notifies those three
 * view models of any changes.
 */
@SysUISingleton
class WifiViewModel
@Inject
constructor(
    connectivityConstants: ConnectivityConstants,
    private val context: Context,
    logger: ConnectivityPipelineLogger,
    interactor: WifiInteractor,
    @Application private val scope: CoroutineScope,
    statusBarPipelineFlags: StatusBarPipelineFlags,
    wifiConstants: WifiConstants,
) {
    /**
     * Returns the drawable resource ID to use for the wifi icon based on the given network.
     * Null if we can't compute the icon.
     */
    @DrawableRes
    private fun WifiNetworkModel.iconResId(): Int? {
        return when (this) {
            is WifiNetworkModel.CarrierMerged -> null
            is WifiNetworkModel.Inactive -> WIFI_NO_NETWORK
            is WifiNetworkModel.Active ->
                when {
                    this.level == null -> null
                    this.isValidated -> WIFI_FULL_ICONS[this.level]
                    else -> WIFI_NO_INTERNET_ICONS[this.level]
                }
        }
    }

    /**
     * Returns the content description for the wifi icon based on the given network.
     * Null if we can't compute the content description.
     */
    private fun WifiNetworkModel.contentDescription(): ContentDescription? {
        return when (this) {
            is WifiNetworkModel.CarrierMerged -> null
            is WifiNetworkModel.Inactive ->
                ContentDescription.Loaded(
                    "${context.getString(WIFI_NO_CONNECTION)},${context.getString(NO_INTERNET)}"
                )
            is WifiNetworkModel.Active ->
                when (this.level) {
                    null -> null
                    else -> {
                        val levelDesc = context.getString(WIFI_CONNECTION_STRENGTH[this.level])
                        when {
                            this.isValidated -> ContentDescription.Loaded(levelDesc)
                            else ->
                                ContentDescription.Loaded(
                                    "$levelDesc,${context.getString(NO_INTERNET)}"
                                )
                        }
                    }
                }
        }
    }

    /** The wifi icon that should be displayed. Null if we shouldn't display any icon. */
    private val wifiIcon: StateFlow<Icon.Resource?> =
        combine(
            interactor.isEnabled,
            interactor.isForceHidden,
            interactor.wifiNetwork,
        ) { isEnabled, isForceHidden, wifiNetwork ->
            if (!isEnabled || isForceHidden || wifiNetwork is WifiNetworkModel.CarrierMerged) {
                return@combine null
            }

            val iconResId = wifiNetwork.iconResId() ?: return@combine null
            val icon = Icon.Resource(iconResId, wifiNetwork.contentDescription())

            return@combine when {
                wifiConstants.alwaysShowIconIfEnabled -> icon
                !connectivityConstants.hasDataCapabilities -> icon
                wifiNetwork is WifiNetworkModel.Active && wifiNetwork.isValidated -> icon
                else -> null
            }
        }
        .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = null)

    /** The wifi activity status. Null if we shouldn't display the activity status. */
    private val activity: Flow<WifiActivityModel?> =
        if (!wifiConstants.shouldShowActivityConfig) {
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
        .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = null)

    private val isActivityInViewVisible: Flow<Boolean> =
         activity
             .map { it?.hasActivityIn == true }
             .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = false)

    private val isActivityOutViewVisible: Flow<Boolean> =
       activity
           .map { it?.hasActivityOut == true }
           .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = false)

    private val isActivityContainerVisible: Flow<Boolean> =
         combine(isActivityInViewVisible, isActivityOutViewVisible) { activityIn, activityOut ->
                    activityIn || activityOut
                }
             .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = false)

    /** A view model for the status bar on the home screen. */
    val home: HomeWifiViewModel =
        HomeWifiViewModel(
            statusBarPipelineFlags,
            wifiIcon,
            isActivityInViewVisible,
            isActivityOutViewVisible,
            isActivityContainerVisible,
        )

    /** A view model for the status bar on keyguard. */
    val keyguard: KeyguardWifiViewModel =
        KeyguardWifiViewModel(
            statusBarPipelineFlags,
            wifiIcon,
            isActivityInViewVisible,
            isActivityOutViewVisible,
            isActivityContainerVisible,
        )

    /** A view model for the status bar in quick settings. */
    val qs: QsWifiViewModel =
        QsWifiViewModel(
            statusBarPipelineFlags,
            wifiIcon,
            isActivityInViewVisible,
            isActivityOutViewVisible,
            isActivityContainerVisible,
        )

    companion object {
        @StringRes
        @VisibleForTesting
        internal val NO_INTERNET = R.string.data_connection_no_internet
    }
}
