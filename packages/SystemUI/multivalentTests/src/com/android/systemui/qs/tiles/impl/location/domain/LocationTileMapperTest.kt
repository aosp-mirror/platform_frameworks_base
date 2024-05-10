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

package com.android.systemui.qs.tiles.impl.location.domain

import android.graphics.drawable.TestStubDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.location.domain.model.LocationTileModel
import com.android.systemui.qs.tiles.impl.location.qsLocationTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.google.common.truth.Truth
import junit.framework.Assert
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LocationTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val qsTileConfig = kosmos.qsLocationTileConfig

    private val mapper by lazy {
        LocationTileMapper(
            context.orCreateTestableResources
                .apply {
                    addOverride(R.drawable.qs_location_icon_off, TestStubDrawable())
                    addOverride(R.drawable.qs_location_icon_on, TestStubDrawable())
                }
                .resources,
            context.theme
        )
    }

    @Test
    fun mapsDisabledDataToInactiveState() {
        val tileState: QSTileState = mapper.map(qsTileConfig, LocationTileModel(false))

        val actualActivationState = tileState.activationState
        Assert.assertEquals(QSTileState.ActivationState.INACTIVE, actualActivationState)
    }

    @Test
    fun mapsEnabledDataToActiveState() {
        val tileState: QSTileState = mapper.map(qsTileConfig, LocationTileModel(true))

        val actualActivationState = tileState.activationState
        Assert.assertEquals(QSTileState.ActivationState.ACTIVE, actualActivationState)
    }

    @Test
    fun mapsEnabledDataToOnIconState() {
        val tileState: QSTileState = mapper.map(qsTileConfig, LocationTileModel(true))

        val expectedIcon = Icon.Loaded(context.getDrawable(R.drawable.qs_location_icon_on)!!, null)
        val actualIcon = tileState.icon()
        Truth.assertThat(actualIcon).isEqualTo(expectedIcon)
    }

    @Test
    fun mapsDisabledDataToOffIconState() {
        val tileState: QSTileState = mapper.map(qsTileConfig, LocationTileModel(false))

        val expectedIcon = Icon.Loaded(context.getDrawable(R.drawable.qs_location_icon_off)!!, null)
        val actualIcon = tileState.icon()
        Truth.assertThat(actualIcon).isEqualTo(expectedIcon)
    }

    @Test
    fun supportsClickAndLongClickActions() {
        val dontCare = true

        val tileState: QSTileState = mapper.map(qsTileConfig, LocationTileModel(dontCare))

        val supportedActions = tileState.supportedActions
        Truth.assertThat(supportedActions)
            .containsExactly(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
    }
}
