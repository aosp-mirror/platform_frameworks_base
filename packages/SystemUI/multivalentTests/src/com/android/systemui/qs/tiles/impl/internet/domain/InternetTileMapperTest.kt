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

package com.android.systemui.qs.tiles.impl.internet.domain

import android.graphics.drawable.TestStubDrawable
import android.os.fakeExecutorHandler
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.internet.domain.model.InternetTileModel
import com.android.systemui.qs.tiles.impl.internet.qsInternetTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_FULL_ICONS
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.satellite.ui.model.SatelliteIconModel
import com.android.systemui.statusbar.pipeline.shared.ui.model.InternetTileIconModel
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class InternetTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val internetTileConfig = kosmos.qsInternetTileConfig
    private val handler = kosmos.fakeExecutorHandler
    private val mapper by lazy {
        InternetTileMapper(
            context.orCreateTestableResources
                .apply {
                    addOverride(R.drawable.ic_qs_no_internet_unavailable, TestStubDrawable())
                    addOverride(R.drawable.ic_satellite_connected_2, TestStubDrawable())
                    addOverride(wifiRes, TestStubDrawable())
                }
                .resources,
            context.theme,
            context,
            handler,
        )
    }

    @Test
    fun withActiveCellularModel_mappedStateMatchesDataModel() {
        val inputModel =
            InternetTileModel.Active(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_available),
                icon = InternetTileIconModel.Cellular(3),
                stateDescription = null,
                contentDescription =
                    ContentDescription.Resource(R.string.quick_settings_internet_label),
            )

        val outputState = mapper.map(internetTileConfig, inputModel)

        val signalDrawable = SignalDrawable(context, handler)
        signalDrawable.setLevel(3)
        val expectedState =
            createInternetTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.quick_settings_networks_available),
                Icon.Loaded(signalDrawable, null),
                null,
                context.getString(R.string.quick_settings_internet_label),
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun withActiveSatelliteModel_mappedStateMatchesDataModel() {
        val inputIcon =
            SignalIconModel.Satellite(
                3,
                Icon.Resource(
                    res = R.drawable.ic_satellite_connected_2,
                    contentDescription =
                        ContentDescription.Resource(
                            R.string.accessibility_status_bar_satellite_good_connection
                        ),
                ),
            )
        val inputModel =
            InternetTileModel.Active(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_available),
                icon = InternetTileIconModel.Satellite(inputIcon.icon),
                stateDescription = null,
                contentDescription =
                    ContentDescription.Resource(
                        R.string.accessibility_status_bar_satellite_good_connection
                    ),
            )

        val outputState = mapper.map(internetTileConfig, inputModel)

        val expectedSatIcon = SatelliteIconModel.fromSignalStrength(3)

        val expectedState =
            createInternetTileState(
                QSTileState.ActivationState.ACTIVE,
                inputModel.secondaryLabel.loadText(context).toString(),
                Icon.Loaded(context.getDrawable(expectedSatIcon!!.res)!!, null),
                expectedSatIcon.res,
                expectedSatIcon.contentDescription.loadContentDescription(context).toString(),
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun withActiveWifiModel_mappedStateMatchesDataModel() {
        val inputModel =
            InternetTileModel.Active(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_available),
                icon = InternetTileIconModel.ResourceId(wifiRes),
                stateDescription = null,
                contentDescription =
                    ContentDescription.Resource(R.string.quick_settings_internet_label),
            )

        val outputState = mapper.map(internetTileConfig, inputModel)

        val expectedState =
            createInternetTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.quick_settings_networks_available),
                Icon.Loaded(context.getDrawable(wifiRes)!!, contentDescription = null),
                wifiRes,
                context.getString(R.string.quick_settings_internet_label),
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun withInactiveModel_mappedStateMatchesDataModel() {
        val inputModel =
            InternetTileModel.Inactive(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_unavailable),
                icon = InternetTileIconModel.ResourceId(R.drawable.ic_qs_no_internet_unavailable),
                stateDescription = null,
                contentDescription =
                    ContentDescription.Resource(R.string.quick_settings_networks_unavailable),
            )

        val outputState = mapper.map(internetTileConfig, inputModel)

        val expectedState =
            createInternetTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(R.string.quick_settings_networks_unavailable),
                Icon.Loaded(
                    context.getDrawable(R.drawable.ic_qs_no_internet_unavailable)!!,
                    contentDescription = null,
                ),
                R.drawable.ic_qs_no_internet_unavailable,
                context.getString(R.string.quick_settings_networks_unavailable),
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createInternetTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
        icon: Icon,
        iconRes: Int? = null,
        contentDescription: String,
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_internet_label)
        return QSTileState(
            icon,
            iconRes,
            label,
            activationState,
            secondaryLabel,
            setOf(
                QSTileState.UserAction.CLICK,
                QSTileState.UserAction.TOGGLE_CLICK,
                QSTileState.UserAction.LONG_CLICK,
            ),
            contentDescription,
            null,
            QSTileState.SideViewIcon.Chevron,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName,
        )
    }

    private companion object {
        val wifiRes = WIFI_FULL_ICONS[4]
    }
}
