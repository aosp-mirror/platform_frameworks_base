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
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.internet.domain.model.InternetTileModel
import com.android.systemui.qs.tiles.impl.internet.qsInternetTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_FULL_ICONS
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class InternetTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val internetTileConfig = kosmos.qsInternetTileConfig
    private val mapper by lazy {
        InternetTileMapper(
            context.orCreateTestableResources
                .apply {
                    addOverride(R.drawable.ic_qs_no_internet_unavailable, TestStubDrawable())
                    addOverride(wifiRes, TestStubDrawable())
                }
                .resources,
            context.theme,
            context
        )
    }

    @Test
    fun withActiveModel_mappedStateMatchesDataModel() {
        val inputModel =
            InternetTileModel.Active(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_available),
                iconId = wifiRes,
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
                context.getString(R.string.quick_settings_internet_label)
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun withInactiveModel_mappedStateMatchesDataModel() {
        val inputModel =
            InternetTileModel.Inactive(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_unavailable),
                iconId = R.drawable.ic_qs_no_internet_unavailable,
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
                    contentDescription = null
                ),
                R.drawable.ic_qs_no_internet_unavailable,
                context.getString(R.string.quick_settings_networks_unavailable)
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createInternetTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
        icon: Icon,
        iconRes: Int,
        contentDescription: String,
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_internet_label)
        return QSTileState(
            { icon },
            iconRes,
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            contentDescription,
            null,
            QSTileState.SideViewIcon.Chevron,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }

    private companion object {
        val wifiRes = WIFI_FULL_ICONS[4]
    }
}
