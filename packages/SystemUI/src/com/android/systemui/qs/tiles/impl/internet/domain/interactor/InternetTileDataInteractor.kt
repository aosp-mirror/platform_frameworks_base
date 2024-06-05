/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.internet.domain.interactor

import android.annotation.StringRes
import android.content.Context
import android.os.UserHandle
import android.text.Html
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.internet.domain.model.InternetTileModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.ethernet.domain.EthernetInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.utils.coroutines.flow.mapLatestConflated
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
/** Observes internet state changes providing the [InternetTileModel]. */
class InternetTileDataInteractor
@Inject
constructor(
    private val context: Context,
    @Main private val mainCoroutineContext: CoroutineContext,
    @Application private val scope: CoroutineScope,
    airplaneModeRepository: AirplaneModeRepository,
    private val connectivityRepository: ConnectivityRepository,
    ethernetInteractor: EthernetInteractor,
    mobileIconsInteractor: MobileIconsInteractor,
    wifiInteractor: WifiInteractor,
) : QSTileDataInteractor<InternetTileModel> {
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
                        icon = Icon.Loaded(context.getDrawable(wifiIcon.icon.res)!!, null),
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
                        Triple(networkNameModel, signalIcon, dataContentDescription)
                    }
                    .mapLatestConflated { (networkNameModel, signalIcon, dataContentDescription) ->
                        when (signalIcon) {
                            is SignalIconModel.Cellular -> {
                                val secondary =
                                    mobileDataContentConcat(
                                        networkNameModel.name,
                                        dataContentDescription
                                    )

                                val drawable =
                                    withContext(mainCoroutineContext) { SignalDrawable(context) }
                                drawable.setLevel(signalIcon.level)
                                val loadedIcon = Icon.Loaded(drawable, null)

                                InternetTileModel.Active(
                                    secondaryTitle = secondary,
                                    icon = loadedIcon,
                                    stateDescription =
                                        ContentDescription.Loaded(secondary.toString()),
                                    contentDescription = ContentDescription.Loaded(internetLabel),
                                )
                            }
                            is SignalIconModel.Satellite -> {
                                val secondary =
                                    signalIcon.icon.contentDescription.loadContentDescription(
                                        context
                                    )
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

    private fun loadString(@StringRes resId: Int): CharSequence? =
        if (resId != 0) {
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
                            iconId = R.drawable.ic_qs_no_internet_unavailable,
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
     * Consumable flow describing the correct state for the InternetTile.
     *
     * Strict ordering of which repo is sending its data to the internet tile. Swaps between each of
     * the interim providers (wifi, mobile, ethernet, or not-connected).
     */
    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<InternetTileModel> =
        connectivityRepository.defaultConnections.flatMapLatest {
            when {
                it.ethernet.isDefault -> ethernetIconFlow
                it.mobile.isDefault || it.carrierMerged.isDefault -> mobileIconFlow
                it.wifi.isDefault -> wifiIconFlow
                else -> notConnectedFlow
            }
        }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)

    private companion object {
        val NOT_CONNECTED_NETWORKS_UNAVAILABLE =
            InternetTileModel.Inactive(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_unavailable),
                iconId = R.drawable.ic_qs_no_internet_unavailable,
                stateDescription = null,
                contentDescription =
                    ContentDescription.Resource(R.string.quick_settings_networks_unavailable),
            )

        fun removeDoubleQuotes(string: String?): String? {
            if (string == null) return null
            return if (string.firstOrNull() == '"' && string.lastOrNull() == '"') {
                string.substring(1, string.length - 1)
            } else string
        }

        fun ContentDescription.toText(): Text =
            when (this) {
                is ContentDescription.Loaded -> Text.Loaded(this.description)
                is ContentDescription.Resource -> Text.Resource(this.res)
            }
    }
}
