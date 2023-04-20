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
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_NO_CONNECTION
import com.android.systemui.R
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.dagger.WifiTableLog
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_FULL_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_INTERNET_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_NETWORK
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModel
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
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
    airplaneModeViewModel: AirplaneModeViewModel,
    connectivityConstants: ConnectivityConstants,
    private val context: Context,
    logger: ConnectivityPipelineLogger,
    @WifiTableLog wifiTableLogBuffer: TableLogBuffer,
    interactor: WifiInteractor,
    @Application private val scope: CoroutineScope,
    statusBarPipelineFlags: StatusBarPipelineFlags,
    wifiConstants: WifiConstants,
) {
    /** Returns the icon to use based on the given network. */
    private fun WifiNetworkModel.icon(): WifiIcon {
        return when (this) {
            is WifiNetworkModel.Unavailable -> WifiIcon.Hidden
            is WifiNetworkModel.CarrierMerged -> WifiIcon.Hidden
            is WifiNetworkModel.Inactive -> WifiIcon.Visible(
                res = WIFI_NO_NETWORK,
                ContentDescription.Loaded(
                    "${context.getString(WIFI_NO_CONNECTION)},${context.getString(NO_INTERNET)}"
                )
            )
            is WifiNetworkModel.Active -> {
                val levelDesc = context.getString(WIFI_CONNECTION_STRENGTH[this.level])
                when {
                    this.isValidated ->
                        WifiIcon.Visible(
                            WIFI_FULL_ICONS[this.level],
                            ContentDescription.Loaded(levelDesc),
                        )
                    else ->
                        WifiIcon.Visible(
                            WIFI_NO_INTERNET_ICONS[this.level],
                            ContentDescription.Loaded(
                                "$levelDesc,${context.getString(NO_INTERNET)}"
                            ),
                        )
                }
            }
        }
    }

    /** The wifi icon that should be displayed. */
    private val wifiIcon: StateFlow<WifiIcon> =
        combine(
            interactor.isEnabled,
            interactor.isDefault,
            interactor.isForceHidden,
            interactor.wifiNetwork,
        ) { isEnabled, isDefault, isForceHidden, wifiNetwork ->
            if (!isEnabled || isForceHidden || wifiNetwork is WifiNetworkModel.CarrierMerged) {
                return@combine WifiIcon.Hidden
            }

            val icon = wifiNetwork.icon()

            return@combine when {
                isDefault -> icon
                wifiConstants.alwaysShowIconIfEnabled -> icon
                !connectivityConstants.hasDataCapabilities -> icon
                wifiNetwork is WifiNetworkModel.Active && wifiNetwork.isValidated -> icon
                else -> WifiIcon.Hidden
            }
        }
            .logDiffsForTable(
                wifiTableLogBuffer,
                columnPrefix = "",
                initialValue = WifiIcon.Hidden,
            )
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = WifiIcon.Hidden
            )

    /** The wifi activity status. Null if we shouldn't display the activity status. */
    private val activity: Flow<DataActivityModel?> =
        if (!connectivityConstants.shouldShowActivityConfig) {
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

    // TODO(b/238425913): It isn't ideal for the wifi icon to need to know about whether the
    //  airplane icon is visible. Instead, we should have a parent StatusBarSystemIconsViewModel
    //  that appropriately knows about both icons and sets the padding appropriately.
    private val isAirplaneSpacerVisible: Flow<Boolean> =
        airplaneModeViewModel.isAirplaneModeIconVisible

    /** A view model for the status bar on the home screen. */
    val home: HomeWifiViewModel =
        HomeWifiViewModel(
            statusBarPipelineFlags,
            wifiIcon,
            isActivityInViewVisible,
            isActivityOutViewVisible,
            isActivityContainerVisible,
            isAirplaneSpacerVisible,
        )

    /** A view model for the status bar on keyguard. */
    val keyguard: KeyguardWifiViewModel =
        KeyguardWifiViewModel(
            statusBarPipelineFlags,
            wifiIcon,
            isActivityInViewVisible,
            isActivityOutViewVisible,
            isActivityContainerVisible,
            isAirplaneSpacerVisible,
        )

    /** A view model for the status bar in quick settings. */
    val qs: QsWifiViewModel =
        QsWifiViewModel(
            statusBarPipelineFlags,
            wifiIcon,
            isActivityInViewVisible,
            isActivityOutViewVisible,
            isActivityContainerVisible,
            isAirplaneSpacerVisible,
        )

    companion object {
        @StringRes
        @VisibleForTesting
        internal val NO_INTERNET = R.string.data_connection_no_internet
    }
}
