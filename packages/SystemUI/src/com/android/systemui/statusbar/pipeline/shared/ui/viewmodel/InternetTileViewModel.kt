/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.content.Context
import android.text.Html
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.ethernet.domain.EthernetInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.ui.model.InternetTileModel
import com.android.systemui.statusbar.pipeline.shared.ui.model.SignalIcon
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * View model for the quick settings [InternetTile]. This model exposes mainly a single flow of
 * InternetTileModel objects, so that updating the tile is as simple as collecting on this state
 * flow and then calling [QSTileImpl.refreshState]
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class InternetTileViewModel
@Inject
constructor(
    airplaneModeRepository: AirplaneModeRepository,
    connectivityRepository: ConnectivityRepository,
    ethernetInteractor: EthernetInteractor,
    mobileIconsInteractor: MobileIconsInteractor,
    wifiInteractor: WifiInteractor,
    private val context: Context,
    @Application scope: CoroutineScope,
) {
    private val internetLabel: String = context.getString(R.string.quick_settings_internet_label)

    // Three symmetrical Flows that can be switched upon based on the value of
    // [DefaultConnectionModel]
    private val wifiIconFlow: Flow<InternetTileModel> =
        wifiInteractor.wifiNetwork.flatMapLatest {
            val wifiIcon = WifiIcon.fromModel(it, context, showHotspotInfo = true)
            if (it is WifiNetworkModel.Active && wifiIcon is WifiIcon.Visible) {
                val secondary = removeDoubleQuotes(it.ssid)
                flowOf(
                    InternetTileModel.Active(
                        secondaryTitle = secondary,
                        icon = ResourceIcon.get(wifiIcon.icon.res),
                        stateDescription = wifiIcon.contentDescription,
                        contentDescription = ContentDescription.Loaded("$internetLabel,$secondary"),
                    )
                )
            } else {
                notConnectedFlow
            }
        }

    private val mobileDataContentName: Flow<CharSequence?> =
        mobileIconsInteractor.activeDataIconInteractor.flatMapLatest {
            if (it == null) {
                flowOf(null)
            } else {
                combine(it.isRoaming, it.networkTypeIconGroup) { isRoaming, networkTypeIconGroup ->
                    val cd = loadString(networkTypeIconGroup.contentDescription)
                    if (isRoaming) {
                        val roaming = context.getString(R.string.data_connection_roaming)
                        if (cd != null) {
                            context.getString(R.string.mobile_data_text_format, roaming, cd)
                        } else {
                            roaming
                        }
                    } else {
                        cd
                    }
                }
            }
        }

    private val mobileIconFlow: Flow<InternetTileModel> =
        mobileIconsInteractor.activeDataIconInteractor.flatMapLatest {
            if (it == null) {
                notConnectedFlow
            } else {
                combine(
                    it.networkName,
                    it.signalLevelIcon,
                    mobileDataContentName,
                ) { networkNameModel, signalIcon, dataContentDescription ->
                    when (signalIcon) {
                        is SignalIconModel.Cellular -> {
                            val secondary =
                                mobileDataContentConcat(
                                    networkNameModel.name,
                                    dataContentDescription
                                )
                            InternetTileModel.Active(
                                secondaryTitle = secondary,
                                icon = SignalIcon(signalIcon.toSignalDrawableState()),
                                stateDescription = ContentDescription.Loaded(secondary.toString()),
                                contentDescription = ContentDescription.Loaded(internetLabel),
                            )
                        }
                        is SignalIconModel.Satellite -> {
                            val secondary =
                                signalIcon.icon.contentDescription.loadContentDescription(context)
                            InternetTileModel.Active(
                                secondaryTitle = secondary,
                                iconId = signalIcon.icon.res,
                                stateDescription = ContentDescription.Loaded(secondary),
                                contentDescription = ContentDescription.Loaded(internetLabel),
                            )
                        }
                    }
                }
            }
        }

    private fun mobileDataContentConcat(
        networkName: String?,
        dataContentDescription: CharSequence?
    ): CharSequence {
        if (dataContentDescription == null) {
            return networkName ?: ""
        }
        if (networkName == null) {
            return Html.fromHtml(dataContentDescription.toString(), 0)
        }

        return Html.fromHtml(
            context.getString(
                R.string.mobile_carrier_text_format,
                networkName,
                dataContentDescription
            ),
            0
        )
    }

    private fun loadString(resId: Int): CharSequence? =
        if (resId > 0) {
            context.getString(resId)
        } else {
            null
        }

    private val ethernetIconFlow: Flow<InternetTileModel> =
        ethernetInteractor.icon.flatMapLatest {
            if (it == null) {
                notConnectedFlow
            } else {
                val secondary = it.contentDescription
                flowOf(
                    InternetTileModel.Active(
                        secondaryLabel = secondary?.toText(),
                        iconId = it.res,
                        stateDescription = null,
                        contentDescription = secondary,
                    )
                )
            }
        }

    private val notConnectedFlow: StateFlow<InternetTileModel> =
        combine(
                wifiInteractor.areNetworksAvailable,
                airplaneModeRepository.isAirplaneMode,
            ) { networksAvailable, isAirplaneMode ->
                when {
                    isAirplaneMode -> {
                        val secondary = context.getString(R.string.status_bar_airplane)
                        InternetTileModel.Inactive(
                            secondaryTitle = secondary,
                            icon = ResourceIcon.get(R.drawable.ic_qs_no_internet_unavailable),
                            stateDescription = null,
                            contentDescription = ContentDescription.Loaded(secondary),
                        )
                    }
                    networksAvailable -> {
                        val secondary =
                            context.getString(R.string.quick_settings_networks_available)
                        InternetTileModel.Inactive(
                            secondaryTitle = secondary,
                            iconId = R.drawable.ic_qs_no_internet_available,
                            stateDescription = null,
                            contentDescription =
                                ContentDescription.Loaded("$internetLabel,$secondary")
                        )
                    }
                    else -> {
                        NOT_CONNECTED_NETWORKS_UNAVAILABLE
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), NOT_CONNECTED_NETWORKS_UNAVAILABLE)

    /**
     * Strict ordering of which repo is sending its data to the internet tile. Swaps between each of
     * the interim providers (wifi, mobile, ethernet, or not-connected)
     */
    private val activeModelProvider: Flow<InternetTileModel> =
        connectivityRepository.defaultConnections.flatMapLatest {
            when {
                it.ethernet.isDefault -> ethernetIconFlow
                it.mobile.isDefault || it.carrierMerged.isDefault -> mobileIconFlow
                it.wifi.isDefault -> wifiIconFlow
                else -> notConnectedFlow
            }
        }

    /** Consumable flow describing the correct state for the InternetTile */
    val tileModel: StateFlow<InternetTileModel> =
        activeModelProvider.stateIn(scope, SharingStarted.WhileSubscribed(), notConnectedFlow.value)

    companion object {
        val NOT_CONNECTED_NETWORKS_UNAVAILABLE =
            InternetTileModel.Inactive(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_unavailable),
                iconId = R.drawable.ic_qs_no_internet_unavailable,
                stateDescription = null,
                contentDescription =
                    ContentDescription.Resource(R.string.quick_settings_networks_unavailable),
            )

        private fun removeDoubleQuotes(string: String?): String? {
            if (string == null) return null
            val length = string.length
            return if (length > 1 && string[0] == '"' && string[length - 1] == '"') {
                string.substring(1, length - 1)
            } else string
        }

        private fun ContentDescription.toText(): Text =
            when (this) {
                is ContentDescription.Loaded -> Text.Loaded(this.description)
                is ContentDescription.Resource -> Text.Resource(this.res)
            }
    }
}
